package com.bdreview.platform.promo;

import com.bdreview.platform.promo.PromoEnums.BusinessPostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BusinessPostRepository extends JpaRepository<BusinessPost, UUID> {

    List<BusinessPost> findByPostIdIn(Collection<UUID> postIds);

    List<BusinessPost> findByBusinessIdOrderByCreatedAtDesc(UUID businessId);

    List<BusinessPost> findByStatusOrderByCreatedAtAsc(BusinessPostStatus status);

    /** Weekly limit: posts that were submitted (anything past DRAFT) in the window. */
    @Query("""
            SELECT count(bp) FROM BusinessPost bp
            WHERE bp.businessId = :businessId AND bp.status <> :draft
              AND (bp.createdAt >= :since OR bp.publishedAt >= :since)
            """)
    long countSubmittedSince(@Param("businessId") UUID businessId, @Param("since") Instant since,
                             @Param("draft") BusinessPostStatus draft);

    /** Candidates for the expiry job: live posts whose own window, event or linked offer is over. */
    @Query(value = """
            SELECT bp.post_id FROM business_post bp
            LEFT JOIN offer o ON o.id = bp.offer_id
            WHERE bp.status IN ('PUBLISHED', 'PENDING_REVIEW')
              AND ( (bp.expires_at IS NOT NULL AND bp.expires_at <= :now)
                 OR (bp.type = 'EVENT' AND bp.event_end IS NOT NULL AND bp.event_end <= :now)
                 OR (bp.offer_id IS NOT NULL AND (o.valid_until <= :now OR o.status <> 'ACTIVE')) )
            """, nativeQuery = true)
    List<UUID> findExpiredCandidates(@Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE BusinessPost bp SET bp.interestedCount = bp.interestedCount + :delta WHERE bp.postId = :postId")
    void adjustInterested(@Param("postId") UUID postId, @Param("delta") int delta);

    List<BusinessPost> findByOfferId(UUID offerId);
}
