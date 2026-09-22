package com.bdreview.platform.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByBusinessIdAndDeletedAtIsNullAndVisibilityStatusNot(
            UUID businessId, VisibilityStatus excluded, Pageable pageable);

    /** Owner dashboard: full review list including NOT_RECOMMENDED, excluding only HIDDEN/soft-deleted. */
    Page<Review> findByBusinessIdAndDeletedAtIsNull(UUID businessId, Pageable pageable);

    /** Admin moderation queue (spec §12): medium-confidence flags across all businesses. */
    Page<Review> findByVisibilityStatusAndDeletedAtIsNull(VisibilityStatus visibilityStatus, Pageable pageable);

    Page<Review> findByUserIdAndDeletedAtIsNull(UUID userId, Pageable pageable);

    /** Trust-signal review count (community author summary, community profile page). */
    long countByUserIdAndDeletedAtIsNull(UUID userId);

    Optional<Review> findByIdAndDeletedAtIsNull(UUID id);

    /** One review per user per business (Yelp-style) — used both to surface "your review" and to block duplicates. */
    Optional<Review> findByBusinessIdAndUserIdAndDeletedAtIsNull(UUID businessId, UUID userId);

    /** Home page "Recent Activity" feed — newest public reviews across every business. */
    Page<Review> findByDeletedAtIsNullAndVisibilityStatusNotOrderByCreatedAtDesc(
            VisibilityStatus excluded, Pageable pageable);

    /**
     * Business detail page "Overall rating" bar chart — count of public
     * reviews per star value (1-5), one grouped query. Row shape: [short
     * rating, Long count]. Public (no auth) even though the review list
     * itself requires login — aggregate counts carry no review content.
     */
    @Query("""
            SELECT r.rating, COUNT(r)
            FROM Review r
            WHERE r.businessId = :businessId AND r.deletedAt IS NULL AND r.visibilityStatus <> :excluded
            GROUP BY r.rating
            """)
    List<Object[]> ratingBreakdown(@Param("businessId") UUID businessId, @Param("excluded") VisibilityStatus excluded);

    /**
     * §7 Business Dashboard — weekly/monthly rating trend as a single grouped
     * query (no N+1: one round trip regardless of review volume).
     */
    @Query(value = """
            SELECT date_trunc(:bucket, r.created_at) AS period,
                   AVG(r.rating) AS avg_rating,
                   COUNT(*) AS review_count
            FROM review r
            WHERE r.business_id = :businessId
              AND r.deleted_at IS NULL
              AND r.visibility_status <> 'HIDDEN'
            GROUP BY period
            ORDER BY period
            """, nativeQuery = true)
    List<Object[]> ratingTrend(@Param("businessId") UUID businessId, @Param("bucket") String bucket);

    // -----------------------------------------------------------------
    // §14 Fake-review pipeline writes visibility/suspicion independently
    // of the rating-aggregate fast path.
    // -----------------------------------------------------------------
    @Modifying
    @Transactional
    @Query("""
            UPDATE Review r SET r.suspicionScore = :score, r.visibilityStatus = :status
            WHERE r.id = :id
            """)
    void applyFakeReviewVerdict(@Param("id") UUID id,
                                @Param("score") short score,
                                @Param("status") VisibilityStatus status);

    @Modifying
    @Transactional
    @Query("UPDATE Review r SET r.deletedAt = :now WHERE r.id = :id AND r.deletedAt IS NULL")
    int softDelete(@Param("id") UUID id, @Param("now") Instant now);

    // -----------------------------------------------------------------
    // §4 Vote counters — informational/sorting-aid only, atomic increments.
    // -----------------------------------------------------------------
    @Modifying
    @Transactional
    @Query("UPDATE Review r SET r.usefulCount = r.usefulCount + :delta WHERE r.id = :id")
    void adjustUsefulCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE Review r SET r.funnyCount = r.funnyCount + :delta WHERE r.id = :id")
    void adjustFunnyCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE Review r SET r.coolCount = r.coolCount + :delta WHERE r.id = :id")
    void adjustCoolCount(@Param("id") UUID id, @Param("delta") int delta);

    /** Count of reviews since the last cached summary was generated (drives §15 trigger at "every 10 new"). */
    long countByBusinessIdAndDeletedAtIsNullAndCreatedAtAfter(UUID businessId, Instant since);

    /**
     * Community feed trust signal ("N reviews" on a post/comment author) —
     * one grouped query per page rather than a per-author round trip. Row
     * shape: [UUID userId, Long count].
     */
    @Query("""
            SELECT r.userId, COUNT(r)
            FROM Review r
            WHERE r.userId IN :userIds AND r.deletedAt IS NULL
            GROUP BY r.userId
            """)
    List<Object[]> countGroupedByUserId(@Param("userIds") List<UUID> userIds);

    // -----------------------------------------------------------------
    // Admin panel (com.bdreview.platform.admin) — full moderation listing
    // across every business and visibility status (the consumer/owner-facing
    // finders above stay scoped to a single business or a single status).
    // -----------------------------------------------------------------
    @Query("""
            SELECT r FROM Review r
            WHERE r.deletedAt IS NULL
              AND (:status IS NULL OR r.visibilityStatus = :status)
            """)
    Page<Review> adminSearch(@Param("status") VisibilityStatus status, Pageable pageable);
}
