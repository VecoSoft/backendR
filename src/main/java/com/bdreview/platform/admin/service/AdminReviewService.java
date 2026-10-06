package com.bdreview.platform.admin.service;

import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.review.Review;
import com.bdreview.platform.review.ReviewRepository;
import com.bdreview.platform.review.VisibilityStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-panel review listing/removal. Visibility overrides (approve / flag /
 * hide) go through the existing {@code moderation.ModerationService}
 * unchanged — this class only adds the plain "any review, any status"
 * listing and the admin hard-moderation delete that service doesn't cover.
 */
@Service
public class AdminReviewService {

    private final ReviewRepository reviewRepository;
    private final BusinessRepository businessRepository;
    private final AuditLogService auditLogService;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public AdminReviewService(ReviewRepository reviewRepository,
                               BusinessRepository businessRepository,
                               AuditLogService auditLogService,
                               org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.reviewRepository = reviewRepository;
        this.businessRepository = businessRepository;
        this.auditLogService = auditLogService;
        this.jdbc = jdbc;
    }

    /** V65 reviews list filters — all optional. */
    public record Filter(VisibilityStatus status, Integer minScore, Integer maxScore, String business, String user,
                         java.time.LocalDate from, java.time.LocalDate to) {
    }

    /** Filtered list (score range, business, user, date) — rows are plain column maps for the table. */
    public Page<java.util.Map<String, Object>> filter(Filter f, int page) {
        var p = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE r.deleted_at IS NULL");
        if (f.status() != null) {
            where.append(" AND r.visibility_status = :status");
            p.addValue("status", f.status().name());
        }
        if (f.minScore() != null) {
            where.append(" AND r.suspicion_score >= :minScore");
            p.addValue("minScore", f.minScore());
        }
        if (f.maxScore() != null) {
            where.append(" AND r.suspicion_score <= :maxScore");
            p.addValue("maxScore", f.maxScore());
        }
        if (f.business() != null && !f.business().isBlank()) {
            where.append(" AND (b.name ILIKE :business OR b.id::text = :businessExact)");
            p.addValue("business", "%" + f.business().trim() + "%").addValue("businessExact", f.business().trim());
        }
        if (f.user() != null && !f.user().isBlank()) {
            where.append(" AND (u.name ILIKE :user OR u.phone_number LIKE :user OR u.id::text = :userExact)");
            p.addValue("user", "%" + f.user().trim() + "%").addValue("userExact", f.user().trim());
        }
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Dhaka");
        if (f.from() != null) {
            where.append(" AND r.created_at >= :from");
            p.addValue("from", java.sql.Timestamp.from(f.from().atStartOfDay(zone).toInstant()));
        }
        if (f.to() != null) {
            where.append(" AND r.created_at < :to");
            p.addValue("to", java.sql.Timestamp.from(f.to().plusDays(1).atStartOfDay(zone).toInstant()));
        }
        String from = " FROM review r JOIN business b ON b.id = r.business_id JOIN app_user u ON u.id = r.user_id" + where;
        Long total = jdbc.queryForObject("SELECT count(*)" + from, p, Long.class);
        p.addValue("limit", 20).addValue("offset", (long) page * 20);
        var rows = jdbc.queryForList("SELECT r.id, r.rating, r.content, r.visibility_status, r.suspicion_score, r.created_at, "
                + "b.id AS business_id, b.name AS business_name, u.id AS user_id, u.name AS user_name, u.phone_number"
                + from + " ORDER BY r.created_at DESC LIMIT :limit OFFSET :offset", p);
        return new org.springframework.data.domain.PageImpl<>(rows, PageRequest.of(page, 20), total == null ? 0 : total);
    }

    /**
     * Bulk Hide / Not recommended (V65). Keeps the business aggregates right (only RECOMMENDED
     * reviews count) and writes one audit entry per review with the before/after status.
     */
    @Transactional
    public int bulkSetVisibility(java.util.Collection<UUID> ids, VisibilityStatus target, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new com.bdreview.platform.common.BadRequestException("A reason is required.");
        }
        if (target != VisibilityStatus.HIDDEN && target != VisibilityStatus.NOT_RECOMMENDED) {
            throw new com.bdreview.platform.common.BadRequestException("Unknown action.");
        }
        if (ids == null || ids.isEmpty()) {
            throw new com.bdreview.platform.common.BadRequestException("Select at least one review.");
        }
        int changed = 0;
        for (UUID id : ids) {
            Review review = reviewRepository.findByIdAndDeletedAtIsNull(id).orElse(null);
            if (review == null || review.getVisibilityStatus() == target) {
                continue;
            }
            VisibilityStatus before = review.getVisibilityStatus();
            review.setVisibilityStatus(target);
            reviewRepository.save(review);
            if (before == VisibilityStatus.RECOMMENDED) {
                businessRepository.applyRatingAggregateDelta(review.getBusinessId(), -review.getRating(), -1);
            }
            auditLogService.record("REVIEW", id, "REVIEW_" + target.name() + "_BULK", reason.trim(),
                    java.util.Map.of("visibilityStatus", before.name()), java.util.Map.of("visibilityStatus", target.name()));
            changed++;
        }
        return changed;
    }

    public Page<Review> search(VisibilityStatus status, int page) {
        return reviewRepository.adminSearch(status, PageRequest.of(page, 20));
    }

    public Review get(UUID id) {
        return reviewRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
    }

    /** Mirrors the aggregate-reconciliation in {@code review.ReviewService#delete}, without the ownership check. */
    @Transactional
    public void delete(UUID reviewId, UUID adminId, String notes) {
        Review review = get(reviewId);
        reviewRepository.softDelete(reviewId, Instant.now());
        if (review.getVisibilityStatus() == VisibilityStatus.RECOMMENDED) {
            businessRepository.applyRatingAggregateDelta(review.getBusinessId(), -review.getRating(), -1);
        }
        auditLogService.log("REVIEW", reviewId, "DELETED", adminId, notes);
    }
}
