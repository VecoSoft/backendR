package com.bdreview.platform.community.moderation;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PhoneNumberUtils;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.*;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import com.bdreview.platform.report.Report;
import com.bdreview.platform.report.ReportRepository;
import com.bdreview.platform.report.ReportStatus;
import com.bdreview.platform.report.ReportTargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Every moderator/admin action on community content and members. Rules enforced here (in
 * addition to the controllers' {@code @PreAuthorize}):
 * <ul>
 *   <li>Every method re-checks the caller's role server-side: {@link #requireStaff()} (ADMIN or
 *       MODERATOR) or {@link #requireAdmin()} (settings-level powers: hard delete, reveal
 *       identity, role changes).</li>
 *   <li>Every action writes an audit_log row: actor, role, action, target type + id, reason,
 *       before/after JSON, IP (see AuditLogService#record).</li>
 *   <li>Removal is always soft (status REMOVED + removedBy/removedReason/removedAt) and
 *       restorable; votes, comments and counters are untouched. Hard delete is ADMIN-only,
 *       needs a reason and a typed confirmation.</li>
 *   <li>Reason is mandatory for every negative action.</li>
 * </ul>
 */
@Service
public class CommunityModerationService {

    private static final Logger log = LoggerFactory.getLogger(CommunityModerationService.class);

    public enum RestrictionDuration {
        H1(1, ChronoUnit.HOURS), H24(24, ChronoUnit.HOURS), D7(7, ChronoUnit.DAYS), D30(30, ChronoUnit.DAYS),
        CUSTOM(0, null), PERMANENT(0, null);

        final long amount;
        final ChronoUnit unit;

        RestrictionDuration(long amount, ChronoUnit unit) {
            this.amount = amount;
            this.unit = unit;
        }
    }

    /** Report-queue outcomes (spec 3.D). */
    public enum ReportAction { DISMISS, REMOVE, REMOVE_WARN, REMOVE_MUTE, REMOVE_SUSPEND }

    public enum BulkPostAction { HIDE, REMOVE, RESTORE, LOCK, UNLOCK, FEATURE, UNFEATURE, UNPIN, APPROVE }

    public static final String HARD_DELETE_CONFIRMATION = "DELETE";

    private final CommunityPostRepository postRepository;
    private final CommunityPostCommentRepository commentRepository;
    private final CommunityPostPhotoRepository photoRepository;
    private final CommunityPostMentionRepository mentionRepository;
    private final CommunityTopicRepository topicRepository;
    private final UserRepository userRepository;
    private final CommunityRestrictionRepository restrictionRepository;
    private final ReportRepository reportRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final CommunityPostService postService;
    private final CommunityUsernameService usernameService;
    private final CommunityPolicyService policy;
    private final JdbcTemplate jdbcTemplate;

    public CommunityModerationService(CommunityPostRepository postRepository,
                                      CommunityPostCommentRepository commentRepository,
                                      CommunityPostPhotoRepository photoRepository,
                                      CommunityPostMentionRepository mentionRepository,
                                      CommunityTopicRepository topicRepository,
                                      UserRepository userRepository,
                                      CommunityRestrictionRepository restrictionRepository,
                                      ReportRepository reportRepository,
                                      AuditLogService auditLogService,
                                      NotificationService notificationService,
                                      CommunityPostService postService,
                                      CommunityUsernameService usernameService,
                                      CommunityPolicyService policy,
                                      JdbcTemplate jdbcTemplate) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.photoRepository = photoRepository;
        this.mentionRepository = mentionRepository;
        this.topicRepository = topicRepository;
        this.userRepository = userRepository;
        this.restrictionRepository = restrictionRepository;
        this.reportRepository = reportRepository;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.postService = postService;
        this.usernameService = usernameService;
        this.policy = policy;
        this.jdbcTemplate = jdbcTemplate;
    }

    // -----------------------------------------------------------------
    // Role guards
    // -----------------------------------------------------------------

    public static boolean isAdmin() {
        return CurrentUser.hasRole("ADMIN");
    }

    public static boolean isStaff() {
        return CurrentUser.hasRole("ADMIN") || CurrentUser.hasRole("MODERATOR");
    }

    public static void requireStaff() {
        if (!isStaff()) {
            throw new ForbiddenException("Requires role ADMIN or MODERATOR");
        }
    }

    public static void requireAdmin() {
        CurrentUser.requireRole("ADMIN");
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required");
        }
        String trimmed = reason.trim();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }

    private static String optionalReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    // -----------------------------------------------------------------
    // Posts
    // -----------------------------------------------------------------

    public CommunityPost requirePost(UUID postId) {
        return postRepository.findById(postId).orElseThrow(() -> new ResourceNotFoundException("Post not found"));
    }

    @Transactional
    public void hidePost(UUID postId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        CommunityPost post = requirePost(postId);
        Map<String, Object> before = snapshot(post);
        post.setStatus(CommunityContentStatus.HIDDEN);
        post.setHoldReason("MODERATOR");
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, "POST_HIDDEN", why, before, snapshot(post));
    }

    @Transactional
    public void removePost(UUID postId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        CommunityPost post = requirePost(postId);
        if (post.getStatus() == CommunityContentStatus.REMOVED) {
            return;
        }
        Map<String, Object> before = snapshot(post);
        applyRemoval(post, why);
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, "POST_REMOVED", why, before, snapshot(post));
        notifyAuthor(post.getAuthorUserId(), "Your community post was removed",
                "A moderator removed your post. Reason: " + why + ". Please review the community rules.",
                "COMMUNITY_POST", postId);
    }

    private void applyRemoval(CommunityPost post, String why) {
        post.setStatus(CommunityContentStatus.REMOVED);
        post.setRemovedBy(CurrentUser.idOrNull());
        post.setRemovedReason(why);
        post.setRemovedAt(Instant.now());
    }

    @Transactional
    public void restorePost(UUID postId, String reason) {
        requireStaff();
        CommunityPost post = requirePost(postId);
        Map<String, Object> before = snapshot(post);
        boolean wasHeld = post.getStatus() == CommunityContentStatus.PENDING;
        post.setStatus(CommunityContentStatus.ACTIVE);
        post.setHoldReason(null);
        post.setRemovedBy(null);
        post.setRemovedReason(null);
        post.setRemovedAt(null);
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, "POST_RESTORED", optionalReason(reason), before, snapshot(post));
        if (wasHeld) {
            notifyMentions(post);
        }
    }

    @Transactional
    public void setLocked(UUID postId, boolean locked, String reason) {
        requireStaff();
        CommunityPost post = requirePost(postId);
        Map<String, Object> before = snapshot(post);
        post.setLocked(locked);
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, locked ? "POST_LOCKED" : "POST_UNLOCKED",
                optionalReason(reason), before, snapshot(post));
    }

    /** scope: GLOBAL, TOPIC (pinned within its own topic) or AREA (within its area's Nearby feed). */
    @Transactional
    public void pin(UUID postId, String scope, Instant until, String reason) {
        requireStaff();
        String s = scope == null ? "GLOBAL" : scope.toUpperCase(Locale.ROOT);
        if (!Set.of("GLOBAL", "TOPIC", "AREA").contains(s)) {
            throw new BadRequestException("Pin scope must be GLOBAL, TOPIC or AREA");
        }
        if (until != null && until.isBefore(Instant.now())) {
            throw new BadRequestException("Pin expiry must be in the future");
        }
        CommunityPost post = requirePost(postId);
        if ("AREA".equals(s) && post.getAreaId() == null) {
            throw new BadRequestException("This post has no area to pin it in");
        }
        Map<String, Object> before = snapshot(post);
        post.setPinned(true);
        post.setPinScope(s);
        post.setPinnedUntil(until);
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, "POST_PINNED", optionalReason(reason), before, snapshot(post));
    }

    @Transactional
    public void unpin(UUID postId, String reason) {
        requireStaff();
        CommunityPost post = requirePost(postId);
        Map<String, Object> before = snapshot(post);
        post.setPinned(false);
        post.setPinnedUntil(null);
        if (!post.isOfficial()) {
            post.setPinScope(null);
        }
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, "POST_UNPINNED", optionalReason(reason), before, snapshot(post));
    }

    @Transactional
    public void setFeatured(UUID postId, boolean featured, String reason) {
        requireStaff();
        CommunityPost post = requirePost(postId);
        Map<String, Object> before = snapshot(post);
        post.setFeatured(featured);
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, featured ? "POST_FEATURED" : "POST_UNFEATURED",
                optionalReason(reason), before, snapshot(post));
    }

    @Transactional
    public void changeTopic(UUID postId, String topicCode, String reason) {
        requireStaff();
        String code = topicCode == null ? "" : topicCode.trim().toUpperCase(Locale.ROOT);
        if (!topicRepository.existsById(code)) {
            throw new BadRequestException("Unknown topic");
        }
        CommunityPost post = requirePost(postId);
        Map<String, Object> before = snapshot(post);
        post.setTopic(code);
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, "POST_TOPIC_CHANGED", optionalReason(reason), before, snapshot(post));
    }

    /** Between DISCUSSION / QUESTION / RECOMMENDATION only — a POLL's options can't be invented or discarded. */
    @Transactional
    public void changePostType(UUID postId, CommunityPostType type, String reason) {
        requireStaff();
        CommunityPost post = requirePost(postId);
        if (type == null || type == CommunityPostType.POLL || post.getPostType() == CommunityPostType.POLL) {
            throw new BadRequestException("Only Discussion, Question and Review can be switched between");
        }
        Map<String, Object> before = snapshot(post);
        post.setPostType(type);
        if (type != CommunityPostType.QUESTION) {
            post.setClosedAt(null);
        }
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, "POST_TYPE_CHANGED", optionalReason(reason), before, snapshot(post));
    }

    @Transactional
    public void removePhoto(UUID photoId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        CommunityPostPhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found"));
        Map<String, Object> before = Map.of("url", photo.getUrl(), "removed", photo.getRemovedAt() != null);
        photo.setRemovedAt(Instant.now());
        photo.setRemovedBy(CurrentUser.idOrNull());
        photo.setRemovedReason(why);
        photoRepository.save(photo);
        auditLogService.record("COMMUNITY_POST_IMAGE", photoId, "IMAGE_REMOVED", why, before,
                Map.of("url", photo.getUrl(), "removed", true, "postId", photo.getPostId()));
    }

    @Transactional
    public void restorePhoto(UUID photoId, String reason) {
        requireStaff();
        CommunityPostPhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found"));
        photo.setRemovedAt(null);
        photo.setRemovedBy(null);
        photo.setRemovedReason(null);
        photoRepository.save(photo);
        auditLogService.record("COMMUNITY_POST_IMAGE", photoId, "IMAGE_RESTORED", optionalReason(reason),
                Map.of("removed", true), Map.of("removed", false, "postId", photo.getPostId()));
    }

    /** ADMIN only, irreversible: deletes the post and everything hanging off it. */
    @Transactional
    public void hardDeletePost(UUID postId, String reason, String confirmation) {
        requireAdmin();
        String why = requireReason(reason);
        if (!HARD_DELETE_CONFIRMATION.equals(confirmation)) {
            throw new BadRequestException("Type " + HARD_DELETE_CONFIRMATION + " to confirm a permanent delete");
        }
        CommunityPost post = requirePost(postId);
        Map<String, Object> before = snapshot(post);
        before.put("title", post.getTitle());
        before.put("body", post.getBody());
        // Children without ON DELETE CASCADE go first; votes/comments/mentions cascade.
        jdbcTemplate.update("DELETE FROM community_post_poll_vote WHERE poll_id IN (SELECT id FROM community_post_poll WHERE post_id = ?)", postId);
        jdbcTemplate.update("DELETE FROM community_post_poll_option WHERE poll_id IN (SELECT id FROM community_post_poll WHERE post_id = ?)", postId);
        jdbcTemplate.update("DELETE FROM community_post_poll WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM community_post_photo WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM community_question_follow WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM community_question_pass WHERE post_id = ?", postId);
        jdbcTemplate.update("UPDATE community_post_comment SET parent_comment_id = NULL WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM community_post WHERE id = ?", postId);
        auditLogService.record("COMMUNITY_POST", postId, "POST_HARD_DELETED", why, before, null);
    }

    // -----------------------------------------------------------------
    // Pending approval queue (new-user rule, banned words, links, auto-hide)
    // -----------------------------------------------------------------

    @Transactional
    public void approvePost(UUID postId, boolean trustAuthor, String reason) {
        requireStaff();
        CommunityPost post = requirePost(postId);
        if (post.getStatus() != CommunityContentStatus.PENDING && post.getStatus() != CommunityContentStatus.HIDDEN) {
            throw new BadRequestException("This post isn't waiting for review");
        }
        Map<String, Object> before = snapshot(post);
        post.setStatus(CommunityContentStatus.ACTIVE);
        post.setHoldReason(null);
        postRepository.save(post);
        // Open reports that caused an auto-hide are considered reviewed.
        closeOpenReports(ReportTargetType.COMMUNITY_POST, postId, ReportStatus.DISMISSED, "Approved after review");
        auditLogService.record("COMMUNITY_POST", postId, trustAuthor ? "POST_APPROVED_AND_TRUSTED" : "POST_APPROVED",
                optionalReason(reason), before, snapshot(post));
        notifyMentions(post);
        if (trustAuthor) {
            trustUser(post.getAuthorUserId(), "Approved and trusted from the pending queue");
        }
    }

    @Transactional
    public void rejectPost(UUID postId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        CommunityPost post = requirePost(postId);
        Map<String, Object> before = snapshot(post);
        applyRemoval(post, why);
        postRepository.save(post);
        auditLogService.record("COMMUNITY_POST", postId, "POST_REJECTED", why, before, snapshot(post));
        notifyAuthor(post.getAuthorUserId(), "Your community post wasn't approved",
                "A moderator reviewed your post and didn't approve it. Reason: " + why + ".", "COMMUNITY_POST", postId);
    }

    @Transactional
    public void approveComment(UUID commentId, boolean trustAuthor, String reason) {
        requireStaff();
        CommunityPostComment comment = requireComment(commentId);
        if (comment.getStatus() != CommunityContentStatus.PENDING && comment.getStatus() != CommunityContentStatus.HIDDEN) {
            throw new BadRequestException("This comment isn't waiting for review");
        }
        Map<String, Object> before = snapshot(comment);
        boolean wasPending = comment.getStatus() == CommunityContentStatus.PENDING;
        comment.setStatus(CommunityContentStatus.ACTIVE);
        comment.setHoldReason(null);
        commentRepository.save(comment);
        if (wasPending) {
            // A held comment was never counted — count it now that it's live.
            postRepository.adjustCommentCount(comment.getPostId(), 1);
            if (comment.getDepth() == 0) {
                postRepository.adjustAnswerCount(comment.getPostId(), 1);
            }
        }
        closeOpenReports(ReportTargetType.COMMUNITY_COMMENT, commentId, ReportStatus.DISMISSED, "Approved after review");
        auditLogService.record("COMMUNITY_COMMENT", commentId, trustAuthor ? "COMMENT_APPROVED_AND_TRUSTED" : "COMMENT_APPROVED",
                optionalReason(reason), before, snapshot(comment));
        if (trustAuthor) {
            trustUser(comment.getAuthorUserId(), "Approved and trusted from the pending queue");
        }
    }

    @Transactional
    public void rejectComment(UUID commentId, String reason) {
        removeComment(commentId, reason);
    }

    // -----------------------------------------------------------------
    // Comments
    // -----------------------------------------------------------------

    public CommunityPostComment requireComment(UUID commentId) {
        return commentRepository.findById(commentId).orElseThrow(() -> new ResourceNotFoundException("Comment not found"));
    }

    @Transactional
    public void removeComment(UUID commentId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        CommunityPostComment comment = requireComment(commentId);
        if (comment.getStatus() == CommunityContentStatus.REMOVED) {
            return;
        }
        Map<String, Object> before = snapshot(comment);
        comment.setStatus(CommunityContentStatus.REMOVED);
        comment.setRemovedBy(CurrentUser.idOrNull());
        comment.setRemovedReason(why);
        comment.setRemovedAt(Instant.now());
        comment.setBestAnswer(false);
        commentRepository.save(comment);
        auditLogService.record("COMMUNITY_COMMENT", commentId, "COMMENT_REMOVED", why, before, snapshot(comment));
        notifyAuthor(comment.getAuthorUserId(), "Your community comment was removed",
                "A moderator removed your comment. Reason: " + why + ".", "COMMUNITY_POST", comment.getPostId());
    }

    @Transactional
    public void restoreComment(UUID commentId, String reason) {
        requireStaff();
        CommunityPostComment comment = requireComment(commentId);
        Map<String, Object> before = snapshot(comment);
        // Only a comment held at creation (PENDING) was never added to the post's counters;
        // ACTIVE → HIDDEN/REMOVED transitions never touch them.
        boolean wasUncounted = comment.getStatus() == CommunityContentStatus.PENDING;
        comment.setStatus(CommunityContentStatus.ACTIVE);
        comment.setHoldReason(null);
        comment.setRemovedBy(null);
        comment.setRemovedReason(null);
        comment.setRemovedAt(null);
        commentRepository.save(comment);
        if (wasUncounted) {
            postRepository.adjustCommentCount(comment.getPostId(), 1);
            if (comment.getDepth() == 0) {
                postRepository.adjustAnswerCount(comment.getPostId(), 1);
            }
        }
        auditLogService.record("COMMUNITY_COMMENT", commentId, "COMMENT_RESTORED", optionalReason(reason), before, snapshot(comment));
    }

    /** Bulk "remove all comments by this user on this post". Returns how many were removed. */
    @Transactional
    public int removeCommentsByUserOnPost(UUID postId, UUID authorUserId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        int n = 0;
        for (CommunityPostComment c : commentRepository.findAllByPostIdAndAuthorUserIdAndDeletedAtIsNull(postId, authorUserId)) {
            if (c.getStatus() != CommunityContentStatus.REMOVED) {
                removeComment(c.getId(), why);
                n++;
            }
        }
        return n;
    }

    // -----------------------------------------------------------------
    // Bulk post actions
    // -----------------------------------------------------------------

    @Transactional
    public int bulkPosts(Collection<UUID> postIds, BulkPostAction action, String reason) {
        requireStaff();
        if (postIds == null || postIds.isEmpty()) {
            throw new BadRequestException("Select at least one post");
        }
        if (Set.of(BulkPostAction.HIDE, BulkPostAction.REMOVE).contains(action)) {
            requireReason(reason);
        }
        int n = 0;
        for (UUID id : postIds) {
            switch (action) {
                case HIDE -> hidePost(id, reason);
                case REMOVE -> removePost(id, reason);
                case RESTORE -> restorePost(id, reason);
                case LOCK -> setLocked(id, true, reason);
                case UNLOCK -> setLocked(id, false, reason);
                case FEATURE -> setFeatured(id, true, reason);
                case UNFEATURE -> setFeatured(id, false, reason);
                case UNPIN -> unpin(id, reason);
                case APPROVE -> approvePost(id, false, reason);
            }
            n++;
        }
        return n;
    }

    // -----------------------------------------------------------------
    // Reports queue (grouped by target)
    // -----------------------------------------------------------------

    /**
     * Resolves every OPEN report on one target at once. Reporters get a neutral "We reviewed your
     * report" notification — never the other user's name or the outcome details.
     */
    @Transactional
    public int resolveReportsForTarget(ReportTargetType targetType, UUID targetId, ReportAction action,
                                       RestrictionDuration duration, Instant customEnd, String reason) {
        requireStaff();
        if (!Set.of(ReportTargetType.COMMUNITY_POST, ReportTargetType.COMMUNITY_COMMENT, ReportTargetType.COMMUNITY_PROFILE)
                .contains(targetType)) {
            throw new BadRequestException("Only community reports are handled here");
        }
        String why = action == ReportAction.DISMISS ? optionalReason(reason) : requireReason(reason);
        UUID offenderUserId = null;
        if (action != ReportAction.DISMISS) {
            switch (targetType) {
                case COMMUNITY_POST -> {
                    CommunityPost post = requirePost(targetId);
                    offenderUserId = post.getAuthorUserId();
                    removePost(targetId, why);
                }
                case COMMUNITY_COMMENT -> {
                    CommunityPostComment comment = requireComment(targetId);
                    offenderUserId = comment.getAuthorUserId();
                    removeComment(targetId, why);
                }
                case COMMUNITY_PROFILE -> {
                    User user = userRepository.findByCommunityProfileId(targetId)
                            .orElseThrow(() -> new ResourceNotFoundException("Community profile not found"));
                    offenderUserId = user.getId();
                    // "Remove" for a profile = reset the public-facing parts of it.
                    resetAvatar(user.getId(), why);
                }
                default -> {
                }
            }
            switch (action) {
                case REMOVE_WARN -> restrict(offenderUserId, CommunityRestriction.Type.WARN, RestrictionDuration.PERMANENT, null, why);
                case REMOVE_MUTE -> restrict(offenderUserId, CommunityRestriction.Type.MUTE, orDefault(duration), customEnd, why);
                case REMOVE_SUSPEND -> restrict(offenderUserId, CommunityRestriction.Type.SUSPEND, orDefault(duration), customEnd, why);
                default -> {
                }
            }
        }
        ReportStatus outcome = action == ReportAction.DISMISS ? ReportStatus.DISMISSED : ReportStatus.ACTION_TAKEN;
        int closed = closeOpenReports(targetType, targetId, outcome, why);
        // A dismissed report that had auto-hidden the content puts it back.
        if (action == ReportAction.DISMISS) {
            if (targetType == ReportTargetType.COMMUNITY_POST) {
                postRepository.findById(targetId).filter(p -> p.getStatus() == CommunityContentStatus.HIDDEN
                        && CommunityPolicyService.HOLD_REPORTS.equals(p.getHoldReason())).ifPresent(p -> {
                    p.setStatus(CommunityContentStatus.ACTIVE);
                    p.setHoldReason(null);
                    postRepository.save(p);
                });
            } else if (targetType == ReportTargetType.COMMUNITY_COMMENT) {
                commentRepository.findById(targetId).filter(c -> c.getStatus() == CommunityContentStatus.HIDDEN
                        && CommunityPolicyService.HOLD_REPORTS.equals(c.getHoldReason())).ifPresent(c -> {
                    c.setStatus(CommunityContentStatus.ACTIVE);
                    c.setHoldReason(null);
                    commentRepository.save(c);
                });
            }
        }
        auditLogService.record(targetType.name(), targetId, "REPORTS_" + action.name(), why,
                Map.of("openReports", closed), Map.of("outcome", outcome.name()));
        return closed;
    }

    private static RestrictionDuration orDefault(RestrictionDuration d) {
        return d == null ? RestrictionDuration.H24 : d;
    }

    private int closeOpenReports(ReportTargetType targetType, UUID targetId, ReportStatus outcome, String note) {
        List<Report> open = reportRepository.findByTargetTypeAndTargetIdAndStatus(targetType, targetId, ReportStatus.PENDING);
        Instant now = Instant.now();
        UUID resolver = CurrentUser.idOrNull();
        for (Report r : open) {
            r.setStatus(outcome);
            r.setResolutionNote(note);
            r.setResolvedBy(resolver);
            r.setResolvedAt(now);
            r.setReporterNotifiedAt(now);
            reportRepository.save(r);
            notifyQuietly(r.getReporterUserId(),
                    outcome == ReportStatus.ACTION_TAKEN ? NotificationType.REPORT_ACTION_TAKEN : NotificationType.REPORT_DISMISSED,
                    "We reviewed your report",
                    "Thanks for helping keep the community safe — we reviewed your report (Ref: " + r.getReferenceCode() + ").",
                    "REPORT", r.getId());
        }
        return open.size();
    }

    /**
     * Called by ReportService right after a community report is filed: bumps the target's report
     * counter and auto-hides it (HIDDEN, pending review) once enough DIFFERENT users have open
     * reports on it. Runs as SYSTEM in the audit log.
     */
    @Transactional
    public void onCommunityReportFiled(ReportTargetType targetType, UUID targetId) {
        int threshold = policy.config().settings().getAutoModeration().getAutoHideReportThreshold();
        if (targetType == ReportTargetType.COMMUNITY_POST) {
            postRepository.incrementReportCount(targetId);
        } else if (targetType == ReportTargetType.COMMUNITY_COMMENT) {
            commentRepository.incrementReportCount(targetId);
        }
        if (threshold <= 0) {
            return;
        }
        long reporters = reportRepository.countDistinctOpenReporters(targetType, targetId);
        if (reporters < threshold) {
            return;
        }
        if (targetType == ReportTargetType.COMMUNITY_POST) {
            postRepository.findById(targetId).filter(p -> p.getStatus() == CommunityContentStatus.ACTIVE && !p.isOfficial())
                    .ifPresent(p -> {
                        Map<String, Object> before = snapshot(p);
                        p.setStatus(CommunityContentStatus.HIDDEN);
                        p.setHoldReason(CommunityPolicyService.HOLD_REPORTS);
                        postRepository.save(p);
                        auditLogService.recordSystem("COMMUNITY_POST", p.getId(), "POST_AUTO_HIDDEN",
                                reporters + " different users reported this post", before, snapshot(p));
                    });
        } else if (targetType == ReportTargetType.COMMUNITY_COMMENT) {
            commentRepository.findById(targetId).filter(c -> c.getStatus() == CommunityContentStatus.ACTIVE)
                    .ifPresent(c -> {
                        Map<String, Object> before = snapshot(c);
                        c.setStatus(CommunityContentStatus.HIDDEN);
                        c.setHoldReason(CommunityPolicyService.HOLD_REPORTS);
                        commentRepository.save(c);
                        auditLogService.recordSystem("COMMUNITY_COMMENT", c.getId(), "COMMENT_AUTO_HIDDEN",
                                reporters + " different users reported this comment", before, snapshot(c));
                    });
        }
    }

    // -----------------------------------------------------------------
    // Members: restrictions
    // -----------------------------------------------------------------

    public User requireUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    @Transactional
    public CommunityRestriction restrict(UUID userId, CommunityRestriction.Type type, RestrictionDuration duration,
                                         Instant customEnd, String reason) {
        requireStaff();
        String why = requireReason(reason);
        if (type == null) {
            throw new BadRequestException("Choose a restriction type");
        }
        User user = requireUser(userId);
        if (user.getRole() == UserRole.ADMIN || user.isModerator()) {
            throw new BadRequestException("Staff accounts can't be restricted — remove their role first");
        }
        Instant now = Instant.now();
        Instant endsAt;
        if (type == CommunityRestriction.Type.BAN) {
            endsAt = null;
        } else if (type == CommunityRestriction.Type.WARN) {
            endsAt = duration == null || duration == RestrictionDuration.PERMANENT ? null : computeEnd(duration, customEnd, now);
        } else {
            if (duration == null || duration == RestrictionDuration.PERMANENT) {
                throw new BadRequestException("Mute and suspend need an expiry — use Ban for a permanent restriction");
            }
            endsAt = computeEnd(duration, customEnd, now);
        }
        CommunityRestriction r = restrictionRepository.save(CommunityRestriction.builder()
                .userId(userId).type(type).reason(why).startsAt(now).endsAt(endsAt)
                .createdBy(CurrentUser.idOrNull() != null ? CurrentUser.idOrNull() : AuditLogService.SYSTEM_ACTOR)
                .build());
        auditLogService.record("COMMUNITY_USER", userId, "USER_" + type.name(), why, null, restrictionSnapshot(r));
        String title = switch (type) {
            case WARN -> "You've received a community warning";
            case MUTE -> "You've been muted in the community";
            case SUSPEND -> "Your community access is suspended";
            case BAN -> "You've been banned from the community";
        };
        notifyQuietly(userId, NotificationType.COMMUNITY_RESTRICTION, title,
                CommunityPolicyService.restrictionMessage(r, null), "COMMUNITY_RESTRICTION", r.getId());
        return r;
    }

    private static Instant computeEnd(RestrictionDuration duration, Instant customEnd, Instant now) {
        if (duration == RestrictionDuration.CUSTOM) {
            if (customEnd == null || !customEnd.isAfter(now)) {
                throw new BadRequestException("Pick a custom end date in the future");
            }
            return customEnd;
        }
        return now.plus(duration.amount, duration.unit);
    }

    @Transactional
    public void liftRestriction(UUID restrictionId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        CommunityRestriction r = restrictionRepository.findById(restrictionId)
                .orElseThrow(() -> new ResourceNotFoundException("Restriction not found"));
        if (r.getStatus() != CommunityRestriction.Status.ACTIVE) {
            throw new BadRequestException("This restriction is no longer active");
        }
        Map<String, Object> before = restrictionSnapshot(r);
        r.setStatus(CommunityRestriction.Status.LIFTED);
        r.setLiftedAt(Instant.now());
        r.setLiftedBy(CurrentUser.idOrNull());
        r.setLiftReason(why);
        restrictionRepository.save(r);
        auditLogService.record("COMMUNITY_USER", r.getUserId(), "RESTRICTION_LIFTED", why, before, restrictionSnapshot(r));
        notifyQuietly(r.getUserId(), NotificationType.COMMUNITY_RESTRICTION, "Your community restriction was lifted",
                "A moderator lifted your " + r.getType().name().toLowerCase(Locale.ROOT) + ". Welcome back!",
                "COMMUNITY_RESTRICTION", r.getId());
    }

    /** Soft-removes every post and comment by the user (restorable one by one). */
    @Transactional
    public int removeAllContent(UUID userId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        requireUser(userId);
        int n = 0;
        Instant now = Instant.now();
        UUID actor = CurrentUser.idOrNull();
        for (CommunityPost p : postRepository.findAllByAuthorUserIdAndDeletedAtIsNull(userId)) {
            if (p.getStatus() != CommunityContentStatus.REMOVED) {
                p.setStatus(CommunityContentStatus.REMOVED);
                p.setRemovedAt(now);
                p.setRemovedBy(actor);
                p.setRemovedReason(why);
                postRepository.save(p);
                n++;
            }
        }
        for (CommunityPostComment c : commentRepository.findAllByAuthorUserIdAndDeletedAtIsNull(userId)) {
            if (c.getStatus() != CommunityContentStatus.REMOVED) {
                c.setStatus(CommunityContentStatus.REMOVED);
                c.setRemovedAt(now);
                c.setRemovedBy(actor);
                c.setRemovedReason(why);
                c.setBestAnswer(false);
                commentRepository.save(c);
                n++;
            }
        }
        auditLogService.record("COMMUNITY_USER", userId, "USER_CONTENT_REMOVED", why, null, Map.of("itemsRemoved", n));
        notifyQuietly(userId, NotificationType.COMMUNITY_MODERATION, "Your community content was removed",
                "A moderator removed your community posts and comments. Reason: " + why + ".", "COMMUNITY_USER", userId);
        return n;
    }

    @Transactional
    public void resetAvatar(UUID userId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        User user = requireUser(userId);
        Map<String, Object> before = new HashMap<>();
        before.put("communityAvatarUrl", user.getCommunityAvatarUrl());
        user.setCommunityAvatarUrl(null);
        userRepository.save(user);
        auditLogService.record("COMMUNITY_USER", userId, "AVATAR_RESET", why, before, Collections.singletonMap("communityAvatarUrl", null));
        notifyQuietly(userId, NotificationType.COMMUNITY_MODERATION, "Your community avatar was reset",
                "A moderator reset your community avatar. Reason: " + why + ".", "COMMUNITY_USER", userId);
    }

    @Transactional
    public String resetUsername(UUID userId, String reason) {
        requireStaff();
        String why = requireReason(reason);
        User user = requireUser(userId);
        String old = user.getCommunityUsername();
        String replacement = usernameService.suggest();
        user.setCommunityUsername(replacement);
        userRepository.save(user);
        auditLogService.record("COMMUNITY_USER", userId, "USERNAME_RESET", why,
                Collections.singletonMap("communityUsername", old), Map.of("communityUsername", replacement));
        notifyQuietly(userId, NotificationType.COMMUNITY_MODERATION, "Your community username was reset",
                "A moderator reset your community username to " + replacement + ". Reason: " + why
                        + ". You can pick a new one in your account settings.", "COMMUNITY_USER", userId);
        return replacement;
    }

    @Transactional
    public void trustUser(UUID userId, String reason) {
        requireStaff();
        User user = requireUser(userId);
        if (user.isCommunityTrusted()) {
            return;
        }
        user.setCommunityTrusted(true);
        userRepository.save(user);
        auditLogService.record("COMMUNITY_USER", userId, "USER_TRUSTED", optionalReason(reason),
                Map.of("communityTrusted", false), Map.of("communityTrusted", true));
    }

    /** Real identity behind a pseudonymous community profile — ADMIN only, reason required, audit-logged. */
    @Transactional
    public Map<String, Object> revealIdentity(UUID userId, String reason) {
        requireAdmin();
        String why = requireReason(reason);
        User user = requireUser(userId);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("userId", user.getId());
        identity.put("name", user.getName());
        identity.put("phoneNumber", user.getPhoneNumber());
        identity.put("accountType", user.getRole().name());
        identity.put("accountCreatedAt", user.getCreatedAt());
        auditLogService.record("COMMUNITY_USER", userId, "IDENTITY_REVEALED", why, null,
                Map.of("communityUsername", String.valueOf(user.getCommunityUsername())));
        return identity;
    }

    // -----------------------------------------------------------------
    // Staff roles (ADMIN only)
    // -----------------------------------------------------------------

    @Transactional
    public User assignModerator(String rawPhone, UUID accountId, String reason) {
        requireAdmin();
        User user;
        if (accountId != null) {
            user = requireUser(accountId);
        } else {
            String phone = PhoneNumberUtils.normalize(rawPhone);
            List<User> candidates = userRepository.findAllByPhoneNumberAndRoleNot(phone, UserRole.ADMIN);
            if (candidates.isEmpty()) {
                throw new ResourceNotFoundException("No consumer or business account with that phone number");
            }
            // Prefer the consumer account (the one that has a community identity).
            user = candidates.stream().filter(u -> u.getRole() == UserRole.CONSUMER).findFirst().orElse(candidates.get(0));
        }
        if (user.getRole() == UserRole.ADMIN) {
            throw new BadRequestException("Admins already have full access");
        }
        if (user.getPasswordHash() == null) {
            throw new BadRequestException("This account has no password yet, so it couldn't sign in to the admin panel");
        }
        if (user.isModerator()) {
            return user;
        }
        user.setStaffRole(User.STAFF_MODERATOR);
        userRepository.save(user);
        auditLogService.record("STAFF_ROLE", user.getId(), "MODERATOR_ASSIGNED", optionalReason(reason),
                Collections.singletonMap("staffRole", null), Map.of("staffRole", User.STAFF_MODERATOR));
        return user;
    }

    @Transactional
    public void removeModerator(UUID userId, String reason) {
        requireAdmin();
        User user = requireUser(userId);
        if (!user.isModerator()) {
            return;
        }
        user.setStaffRole(null);
        userRepository.save(user);
        auditLogService.record("STAFF_ROLE", userId, "MODERATOR_REMOVED", optionalReason(reason),
                Map.of("staffRole", User.STAFF_MODERATOR), Collections.singletonMap("staffRole", null));
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private void notifyMentions(CommunityPost post) {
        List<UUID> businessIds = mentionRepository.findByPostId(post.getId()).stream()
                .map(CommunityPostMention::getBusinessId).toList();
        try {
            postService.notifyMentionedBusinesses(post, businessIds);
        } catch (RuntimeException e) {
            log.warn("Could not send mention notifications for approved post {}", post.getId(), e);
        }
    }

    private void notifyAuthor(UUID userId, String title, String body, String entityType, UUID entityId) {
        notifyQuietly(userId, NotificationType.COMMUNITY_MODERATION, title, body, entityType, entityId);
    }

    /** A notification failure must never roll back a moderation action. */
    private void notifyQuietly(UUID userId, NotificationType type, String title, String body, String entityType, UUID entityId) {
        if (userId == null) {
            return;
        }
        try {
            notificationService.create(userId, type, title, body, entityType, entityId, NotificationChannel.IN_APP);
        } catch (RuntimeException e) {
            log.warn("Could not notify user {} about a community moderation action", userId, e);
        }
    }

    static Map<String, Object> snapshot(CommunityPost p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", p.getStatus().name());
        m.put("holdReason", p.getHoldReason());
        m.put("topic", p.getTopic());
        m.put("postType", p.getPostType().name());
        m.put("locked", p.isLocked());
        m.put("pinned", p.isPinned());
        m.put("pinScope", p.getPinScope());
        m.put("pinnedUntil", p.getPinnedUntil());
        m.put("featured", p.isFeatured());
        m.put("removedReason", p.getRemovedReason());
        m.put("upvotes", p.getUpvoteCount());
        m.put("downvotes", p.getDownvoteCount());
        m.put("comments", p.getCommentCount());
        return m;
    }

    static Map<String, Object> snapshot(CommunityPostComment c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", c.getStatus().name());
        m.put("holdReason", c.getHoldReason());
        m.put("postId", c.getPostId());
        m.put("removedReason", c.getRemovedReason());
        m.put("bestAnswer", c.isBestAnswer());
        return m;
    }

    static Map<String, Object> restrictionSnapshot(CommunityRestriction r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("restrictionId", r.getId());
        m.put("type", r.getType().name());
        m.put("status", r.getStatus().name());
        m.put("endsAt", r.getEndsAt());
        m.put("reason", r.getReason());
        return m;
    }
}
