package com.bdreview.platform.report;

import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.community.CommunityPost;
import com.bdreview.platform.community.CommunityPostComment;
import com.bdreview.platform.community.CommunityPostCommentRepository;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.moderation.ModerationService;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.review.ReviewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers doc §36 point 17: reporting/resolving a Community post or comment
 * reuses the existing moderation pattern — this only exercises the new
 * COMMUNITY_POST/COMMUNITY_COMMENT branches added to resolve(); the
 * pre-existing REVIEW/LISTING branches are unchanged in behavior (only
 * restructured from if/else into a switch) and aren't re-tested here.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock ReportRepository reportRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock BusinessRepository businessRepository;
    @Mock CommunityPostRepository communityPostRepository;
    @Mock CommunityPostCommentRepository communityPostCommentRepository;
    @Mock OfferRepository offerRepository;
    @Mock ModerationService moderationService;
    @Mock AuditLogService auditLogService;
    @Mock NotificationService notificationService;

    ReportService reportService;
    UUID adminId;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(reportRepository, reviewRepository, businessRepository,
                communityPostRepository, communityPostCommentRepository, offerRepository, moderationService, auditLogService,
                notificationService, 10, 3, 30, 3, (short) 70, null);
        // resolve() self-invokes @Async dispatch methods via `self` — no Spring proxy here, so
        // wire it directly, same pattern as QrServiceTest's `qrService.self = qrService`.
        setSelf(reportService, reportService);

        adminId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                adminId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setSelf(ReportService target, ReportService self) {
        try {
            var field = ReportService.class.getDeclaredField("self");
            field.setAccessible(true);
            field.set(target, self);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void resolvingActionTakenOnACommunityPostSoftDeletesItAndNotifiesTheAuthor() {
        UUID reportId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).targetType(ReportTargetType.COMMUNITY_POST)
                .targetId(postId).reason(ReportReason.SPAM).status(ReportStatus.PENDING)
                .referenceCode("R-1").build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(communityPostRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(authorId).build()));

        reportService.resolve(reportId, ReportStatus.ACTION_TAKEN, "spam confirmed");

        verify(communityPostRepository).softDelete(eq(postId), any());
        verify(reportRepository).save(argThat(r -> r.getStatus() == ReportStatus.ACTION_TAKEN));
        verify(notificationService).create(eq(authorId), eq(com.bdreview.platform.notification.NotificationType.CONTENT_HIDDEN),
                any(), any(), any(), any(), any());
    }

    @Test
    void resolvingActionTakenOnACommunityCommentSoftDeletesItAndDecrementsThePostsCommentCount() {
        UUID reportId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        UUID commentAuthorId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).targetType(ReportTargetType.COMMUNITY_COMMENT)
                .targetId(commentId).reason(ReportReason.OFFENSIVE).status(ReportStatus.PENDING)
                .referenceCode("R-2").build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(communityPostCommentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(CommunityPostComment.builder()
                        .id(commentId).postId(postId).authorUserId(commentAuthorId).build()));

        reportService.resolve(reportId, ReportStatus.ACTION_TAKEN, "harassment");

        verify(communityPostCommentRepository).softDelete(eq(commentId), any());
        verify(communityPostRepository).adjustCommentCount(postId, -1);
    }

    @Test
    void dismissingACommunityReportTakesNoModerationAction() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).targetType(ReportTargetType.COMMUNITY_POST)
                .targetId(UUID.randomUUID()).reason(ReportReason.OTHER).status(ReportStatus.PENDING)
                .referenceCode("R-3").build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        reportService.resolve(reportId, ReportStatus.DISMISSED, "not a violation");

        verify(communityPostRepository, never()).softDelete(any(), any());
        verify(reportRepository).save(argThat(r -> r.getStatus() == ReportStatus.DISMISSED));
    }
}
