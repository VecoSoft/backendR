package com.bdreview.platform.report;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.RateLimitExceededException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.CommunityPost;
import com.bdreview.platform.community.CommunityPostComment;
import com.bdreview.platform.community.CommunityPostCommentRepository;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.community.moderation.CommunityModerationService;
import com.bdreview.platform.community.moderation.CommunityPolicyService;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.moderation.ModerationService;
import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.review.Review;
import com.bdreview.platform.review.ReviewRepository;
import com.bdreview.platform.review.VisibilityStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Spec §11: fixed enum reason (never free text), rate-limited to prevent
 * report-spam abuse. Production-grade workflow additions: auto-triage
 * priority, a reference code + 48h SLA, multi-outcome resolution that reuses
 * the existing moderation hide logic for reviews, and async in-app/SMS
 * notifications at each step of the flow (never the raw internal reasoning —
 * only the generic per-outcome message).
 *
 * Resubmission is intentionally unrestricted: nothing here blocks a reporter
 * (or a content owner disputing a decision) from filing a new report on the
 * same target — a fresh row gets its own reference code, and an admin can
 * mark a redundant one DUPLICATE at resolve time instead of the submit path
 * rejecting it up front.
 */
@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final ReviewRepository reviewRepository;
    private final BusinessRepository businessRepository;
    private final CommunityPostRepository communityPostRepository;
    private final CommunityPostCommentRepository communityPostCommentRepository;
    private final OfferRepository offerRepository;
    private final ModerationService moderationService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final long maxReportsPerHour;
    private final long maxReportsPerTargetPerWindow;
    private final long perTargetWindowDays;
    private final long highPriorityReportCountThreshold;
    private final short highPrioritySuspicionThreshold;
    private final ReportService self;

    public ReportService(ReportRepository reportRepository,
                          ReviewRepository reviewRepository,
                          BusinessRepository businessRepository,
                          CommunityPostRepository communityPostRepository,
                          CommunityPostCommentRepository communityPostCommentRepository,
                          OfferRepository offerRepository,
                          ModerationService moderationService,
                          AuditLogService auditLogService,
                          NotificationService notificationService,
                          @Value("${app.report.max-per-hour:10}") long maxReportsPerHour,
                          @Value("${app.report.max-per-target-per-window:3}") long maxReportsPerTargetPerWindow,
                          @Value("${app.report.per-target-window-days:30}") long perTargetWindowDays,
                          @Value("${app.report.high-priority-report-count-threshold:3}") long highPriorityReportCountThreshold,
                          @Value("${app.fake-review.medium-confidence-max:70}") short highPrioritySuspicionThreshold,
                          @Lazy ReportService self) {
        this.reportRepository = reportRepository;
        this.reviewRepository = reviewRepository;
        this.businessRepository = businessRepository;
        this.communityPostRepository = communityPostRepository;
        this.communityPostCommentRepository = communityPostCommentRepository;
        this.offerRepository = offerRepository;
        this.moderationService = moderationService;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.maxReportsPerHour = maxReportsPerHour;
        this.maxReportsPerTargetPerWindow = maxReportsPerTargetPerWindow;
        this.perTargetWindowDays = perTargetWindowDays;
        this.highPriorityReportCountThreshold = highPriorityReportCountThreshold;
        this.highPrioritySuspicionThreshold = highPrioritySuspicionThreshold;
        this.self = self;
    }

    /** V67 editable notification texts (System → Notifications → Templates) — setter-injected (unit tests construct this service by hand). */
    private com.bdreview.platform.notification.NotificationTemplateService templates;

    @org.springframework.beans.factory.annotation.Autowired
    void setTemplates(com.bdreview.platform.notification.NotificationTemplateService templates) {
        this.templates = templates;
    }

    private static final Set<ReportTargetType> COMMUNITY_TARGETS = Set.of(
            ReportTargetType.COMMUNITY_POST, ReportTargetType.COMMUNITY_COMMENT, ReportTargetType.COMMUNITY_PROFILE);

    // Community moderation hooks (V56) — setter-injected so the constructor (and its unit tests)
    // stay unchanged; null outside a full Spring context.
    private CommunityModerationService communityModerationService;
    private CommunityPolicyService communityPolicyService;
    private UserRepository userRepository;

    @Autowired(required = false)
    void setCommunityHooks(@Lazy CommunityModerationService communityModerationService,
                           CommunityPolicyService communityPolicyService,
                           UserRepository userRepository) {
        this.communityModerationService = communityModerationService;
        this.communityPolicyService = communityPolicyService;
        this.userRepository = userRepository;
    }

    @Transactional
    public Report create(UUID reporterUserId, CreateReportRequest request) {
        boolean communityTarget = COMMUNITY_TARGETS.contains(request.targetType());
        if (communityTarget && communityPolicyService != null) {
            // Muted/suspended/banned members can't report either; community "reports per day" tier limit.
            User reporter = userRepository.findById(reporterUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            communityPolicyService.assertCanWrite(reporter, CommunityPolicyService.Action.REPORT);
            requireCommunityTargetExists(request.targetType(), request.targetId());
        }
        long recent = reportRepository.countByReporterUserIdAndCreatedAtAfter(
                reporterUserId, Instant.now().minus(1, ChronoUnit.HOURS));
        if (recent >= maxReportsPerHour) {
            throw new RateLimitExceededException("Too many reports filed recently — please try again later.");
        }

        // Same reporter, same target: caps repeat reports of the one thing, independent of the
        // global per-hour limit above (which caps report *volume*, not *targeting the same thing*).
        long recentForTarget = reportRepository.countByReporterUserIdAndTargetTypeAndTargetIdAndCreatedAtAfter(
                reporterUserId, request.targetType(), request.targetId(),
                Instant.now().minus(perTargetWindowDays, ChronoUnit.DAYS));
        if (recentForTarget >= maxReportsPerTargetPerWindow) {
            throw new RateLimitExceededException(
                    "You've already reported this " + perTargetWindowDays + " days ago or more recently — "
                            + "please wait before reporting it again.");
        }

        String referenceCode = "R-" + reportRepository.nextReferenceSeqValue();

        Report report = reportRepository.save(Report.builder()
                .reporterUserId(reporterUserId)
                .targetType(request.targetType())
                .targetId(request.targetId())
                .reason(request.reason())
                .status(ReportStatus.PENDING)
                .referenceCode(referenceCode)
                .priority(determinePriority(request))
                .build());

        if (communityTarget && communityModerationService != null) {
            communityModerationService.onCommunityReportFiled(request.targetType(), request.targetId());
        }

        self.dispatchSubmissionNotification(report.getId(), report.getReporterUserId(), report.getReferenceCode());
        return report;
    }

    private static String removalReason(Report report, String resolutionNote) {
        return resolutionNote != null && !resolutionNote.isBlank()
                ? resolutionNote.trim()
                : "Reported for " + report.getReason().label();
    }

    private void requireCommunityTargetExists(ReportTargetType type, UUID id) {
        boolean exists = switch (type) {
            case COMMUNITY_POST -> communityPostRepository.findByIdAndDeletedAtIsNull(id).isPresent();
            case COMMUNITY_COMMENT -> communityPostCommentRepository.findByIdAndDeletedAtIsNull(id).isPresent();
            case COMMUNITY_PROFILE -> userRepository.findByCommunityProfileId(id).isPresent();
            default -> true;
        };
        if (!exists) {
            throw new ResourceNotFoundException("The content you're reporting could not be found");
        }
    }

    /**
     * Spec: OFFENSIVE reports, reports against an already-suspicious review, or a target that
     * already has multiple reports against it (any reporter, any status) jump the queue. Report
     * volume only ever affects *priority* here — it never auto-decides the outcome; an admin
     * always makes that call, with the target's report count visible to them (see
     * {@link #reportCountForTarget}) as context.
     */
    private Priority determinePriority(CreateReportRequest request) {
        if (request.reason() == ReportReason.OFFENSIVE) {
            return Priority.HIGH;
        }
        if (request.targetType() == ReportTargetType.REVIEW) {
            boolean alreadySuspicious = reviewRepository.findByIdAndDeletedAtIsNull(request.targetId())
                    .map(review -> review.getSuspicionScore() > highPrioritySuspicionThreshold)
                    .orElse(false);
            if (alreadySuspicious) {
                return Priority.HIGH;
            }
        }
        long existingReportsForTarget = reportRepository.countByTargetTypeAndTargetId(request.targetType(), request.targetId());
        if (existingReportsForTarget + 1 >= highPriorityReportCountThreshold) {
            return Priority.HIGH;
        }
        return Priority.NORMAL;
    }

    public Page<Report> queue(Pageable pageable) {
        return reportRepository.findByStatus(ReportStatus.PENDING, pageable);
    }

    /** Admin-facing trust signal for a reporter, computed from their own report history — no new table. */
    public ReporterCredibility reporterCredibility(UUID reporterUserId) {
        long total = reportRepository.countByReporterUserId(reporterUserId);
        long actionTaken = reportRepository.countByReporterUserIdAndStatus(reporterUserId, ReportStatus.ACTION_TAKEN);
        return new ReporterCredibility(total, actionTaken);
    }

    /** Admin-queue context: how many reports (any reporter, any status) exist against this target. */
    public long reportCountForTarget(ReportTargetType targetType, UUID targetId) {
        return reportRepository.countByTargetTypeAndTargetId(targetType, targetId);
    }

    @Transactional
    public void resolve(UUID reportId, ReportStatus outcome, String resolutionNote) {
        CurrentUser.requireRole("ADMIN");
        if (outcome == null || !outcome.isResolutionOutcome()) {
            throw new BadRequestException("outcome must be one of ACTION_TAKEN, DISMISSED, DUPLICATE");
        }
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
        UUID adminId = CurrentUser.id();
        Instant now = Instant.now();

        // "Target owner" = whoever's content/listing the report was actually about — the review's
        // author, the business's owner, or a community post/comment's author.
        UUID targetOwnerId = null;
        if (outcome == ReportStatus.ACTION_TAKEN) {
            targetOwnerId = switch (report.getTargetType()) {
                case REVIEW -> {
                    UUID owner = reviewRepository.findByIdAndDeletedAtIsNull(report.getTargetId())
                            .map(Review::getUserId)
                            .orElse(null);
                    // Reuse the existing moderation override rather than duplicating the
                    // visibility/rating-aggregate reconciliation logic.
                    moderationService.resolveFlaggedReview(report.getTargetId(), VisibilityStatus.HIDDEN, adminId, resolutionNote);
                    yield owner;
                }
                case LISTING -> {
                    // A confirmed violation actually flags the listing — a visible, public
                    // warning carrying the report's reason, not just a notification with no real
                    // effect. Admins can still soft-delete the listing manually from the
                    // Businesses admin screen if the situation warrants going further than a flag.
                    UUID owner = businessRepository.findById(report.getTargetId())
                            .map(Business::getOwnerUserId)
                            .orElse(null);
                    businessRepository.flag(report.getTargetId(), report.getReason().name(), now);
                    yield owner;
                }
                case COMMUNITY_POST -> {
                    // V56: a restorable moderator removal (status REMOVED, audit-logged, author
                    // notified by the moderation service) instead of the old hard-to-undo soft delete.
                    UUID owner = communityPostRepository.findByIdAndDeletedAtIsNull(report.getTargetId())
                            .map(CommunityPost::getAuthorUserId)
                            .orElse(null);
                    if (communityModerationService != null && owner != null) {
                        communityModerationService.removePost(report.getTargetId(), removalReason(report, resolutionNote));
                        yield null;
                    }
                    communityPostRepository.softDelete(report.getTargetId(), now);
                    yield owner;
                }
                case COMMUNITY_COMMENT -> {
                    CommunityPostComment comment = communityPostCommentRepository
                            .findByIdAndDeletedAtIsNull(report.getTargetId()).orElse(null);
                    if (comment != null && communityModerationService != null) {
                        communityModerationService.removeComment(comment.getId(), removalReason(report, resolutionNote));
                        yield null;
                    }
                    if (comment != null) {
                        communityPostCommentRepository.softDelete(comment.getId(), now);
                        communityPostRepository.adjustCommentCount(comment.getPostId(), -1);
                    }
                    yield comment == null ? null : comment.getAuthorUserId();
                }
                case COMMUNITY_PROFILE -> null; // handled from the Community → Reports queue
                case OFFER -> {
                    // Same status transition the owner's own "cancel offer" action performs.
                    Offer offer = offerRepository.findById(report.getTargetId()).orElse(null);
                    UUID owner = offer == null ? null
                            : businessRepository.findById(offer.getBusinessId()).map(Business::getOwnerUserId).orElse(null);
                    if (offer != null) {
                        offerRepository.cancel(offer.getId());
                    }
                    yield owner;
                }
            };
        }

        report.setStatus(outcome);
        report.setResolutionNote(resolutionNote);
        report.setResolvedBy(adminId);
        report.setResolvedAt(now);
        report.setReporterNotifiedAt(now);
        if (targetOwnerId != null) {
            report.setTargetOwnerNotifiedAt(now);
        }
        reportRepository.save(report);

        auditLogService.log("REPORT", reportId, outcome.name(), adminId, resolutionNote);

        self.dispatchResolutionNotifications(reportId, outcome, report.getTargetType(), report.getReason(),
                report.getReporterUserId(), report.getReferenceCode(), report.getTargetId(), targetOwnerId);
    }

    /**
     * Takes the reporter id/reference code as parameters rather than re-reading the Report row —
     * this runs on a separate thread via @Async, which can start before create()'s own transaction
     * has committed; re-querying by id here would then find nothing and silently drop the
     * notification. Passing the already-loaded values avoids that race entirely.
     */
    @Async
    public void dispatchSubmissionNotification(UUID reportId, UUID reporterUserId, String referenceCode) {
        notificationService.create(reporterUserId, NotificationType.REPORT_SUBMITTED,
                "Report submitted",
                "Your report (Ref: " + referenceCode + ") has been submitted, we'll review it shortly.",
                "REPORT", reportId, NotificationChannel.IN_APP);
    }

    /**
     * Same reasoning as dispatchSubmissionNotification: takes everything it needs as parameters
     * (already loaded in resolve() before the transaction commits) instead of re-querying the
     * Report row from a separate async thread, to avoid a commit-visibility race. Only ever sends
     * the generic outcome-based message below — never the admin's note or which fake-review signal
     * fired, per spec: "never send the reporter the exact internal reasoning."
     */
    @Async
    public void dispatchResolutionNotifications(UUID reportId, ReportStatus outcome, ReportTargetType targetType,
                                                 ReportReason reason, UUID reporterUserId, String referenceCode,
                                                 UUID targetId, UUID targetOwnerId) {
        if (templates != null && (outcome == ReportStatus.ACTION_TAKEN || outcome == ReportStatus.DISMISSED)) {
            // V67: editable text (System → Notifications → Templates), in the reporter's language.
            templates.notify(reporterUserId, outcome == ReportStatus.ACTION_TAKEN
                            ? com.bdreview.platform.notification.NotificationTemplateService.Key.REPORT_ACTION_TAKEN
                            : com.bdreview.platform.notification.NotificationTemplateService.Key.REPORT_DISMISSED,
                    java.util.Map.of("referenceCode", referenceCode),
                    outcome == ReportStatus.ACTION_TAKEN ? NotificationType.REPORT_ACTION_TAKEN : NotificationType.REPORT_DISMISSED,
                    "REPORT", reportId);
        } else if (outcome == ReportStatus.ACTION_TAKEN) {
            notificationService.create(reporterUserId, NotificationType.REPORT_ACTION_TAKEN,
                    "Report resolved",
                    "Your report (Ref: " + referenceCode + ") has been reviewed — action was taken.",
                    "REPORT", reportId, NotificationChannel.IN_APP);
        } else if (outcome == ReportStatus.DISMISSED) {
            notificationService.create(reporterUserId, NotificationType.REPORT_DISMISSED,
                    "Report resolved",
                    "Your report (Ref: " + referenceCode + ") has been reviewed — insufficient evidence was found.",
                    "REPORT", reportId, NotificationChannel.IN_APP);
        }
        // DUPLICATE: no reporter-facing message defined by spec — the report simply closes.

        if (outcome != ReportStatus.ACTION_TAKEN || targetOwnerId == null) {
            return;
        }
        // Discloses the report's fixed reason category (SPAM/FAKE/OFFENSIVE/OTHER) so the target
        // owner understands *what kind* of violation was found — still never the admin's private
        // resolution note or which specific report/signal triggered it.
        switch (targetType) {
            case REVIEW -> {
                String title = "Review removed";
                String body = "One of your reviews was removed for " + reason.label() + ". "
                        + "Please review our community guidelines.";
                notificationService.create(targetOwnerId, NotificationType.CONTENT_HIDDEN, title, body,
                        "REVIEW", targetId, NotificationChannel.IN_APP);
                notificationService.create(targetOwnerId, NotificationType.CONTENT_HIDDEN, title, body,
                        "REVIEW", targetId, NotificationChannel.SMS);
            }
            case LISTING -> {
                String title = "Listing flagged";
                String body = "Your business listing was flagged for " + reason.label() + " following a user report. "
                        + "Please review our community guidelines to keep your listing active.";
                notificationService.create(targetOwnerId, NotificationType.LISTING_FLAGGED, title, body,
                        "BUSINESS", targetId, NotificationChannel.IN_APP);
                notificationService.create(targetOwnerId, NotificationType.LISTING_FLAGGED, title, body,
                        "BUSINESS", targetId, NotificationChannel.SMS);
            }
            case COMMUNITY_POST, COMMUNITY_COMMENT -> {
                String noun = targetType == ReportTargetType.COMMUNITY_POST ? "post" : "comment";
                String title = "Community " + noun + " removed";
                String body = "Your community " + noun + " was removed for " + reason.label() + ". "
                        + "Please review our community guidelines.";
                notificationService.create(targetOwnerId, NotificationType.CONTENT_HIDDEN, title, body,
                        "COMMUNITY_" + noun.toUpperCase(java.util.Locale.ROOT), targetId, NotificationChannel.IN_APP);
            }
            case OFFER -> {
                String title = "Offer cancelled";
                String body = "Your offer was cancelled for " + reason.label() + ". "
                        + "Please review our community guidelines.";
                notificationService.create(targetOwnerId, NotificationType.CONTENT_HIDDEN, title, body,
                        "OFFER", targetId, NotificationChannel.IN_APP);
            }
        }
    }
}
