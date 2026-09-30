package com.bdreview.platform.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    // ---- Community moderation (V56) ----

    /** Community "reports per day" limit — see community.moderation.CommunityPolicyService. */
    long countByReporterUserIdAndTargetTypeInAndCreatedAtAfter(
            UUID reporterUserId, Collection<ReportTargetType> targetTypes, Instant since);

    /** Auto-hide threshold counts DIFFERENT reporters with an open report on the target. */
    @Query("""
            SELECT COUNT(DISTINCT r.reporterUserId) FROM Report r
            WHERE r.targetType = :targetType AND r.targetId = :targetId
              AND r.status = com.bdreview.platform.report.ReportStatus.PENDING
            """)
    long countDistinctOpenReporters(@Param("targetType") ReportTargetType targetType, @Param("targetId") UUID targetId);

    List<Report> findByTargetTypeAndTargetIdAndStatus(ReportTargetType targetType, UUID targetId, ReportStatus status);

    List<Report> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType targetType, UUID targetId);

    List<Report> findByTargetTypeInAndTargetIdInOrderByCreatedAtDesc(Collection<ReportTargetType> targetTypes, Collection<UUID> targetIds);

    long countByTargetTypeInAndStatus(Collection<ReportTargetType> targetTypes, ReportStatus status);

    /** Rate-limit backing query: caps how many reports one user can file in a short window (spec §11). */
    long countByReporterUserIdAndCreatedAtAfter(UUID reporterUserId, Instant since);

    /** Rate-limit backing query: caps repeat reports from the same reporter against the same target. */
    long countByReporterUserIdAndTargetTypeAndTargetIdAndCreatedAtAfter(
            UUID reporterUserId, ReportTargetType targetType, UUID targetId, Instant since);

    /** Auto-triage + admin-queue context: total reports (any reporter, any status) against one target. */
    long countByTargetTypeAndTargetId(ReportTargetType targetType, UUID targetId);

    Page<Report> findByStatus(ReportStatus status, Pageable pageable);

    /** Reporter credibility (admin queue): total reports this user has ever filed. */
    long countByReporterUserId(UUID reporterUserId);

    /** Reporter credibility (admin queue): how many of those were upheld (ACTION_TAKEN). */
    long countByReporterUserIdAndStatus(UUID reporterUserId, ReportStatus status);

    /** Backs the "R-<n>" reference code — one fresh value per report, assigned in ReportService#create. */
    @Query(value = "SELECT nextval('report_reference_seq')", nativeQuery = true)
    long nextReferenceSeqValue();
}
