package com.bdreview.platform.offer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface OfferRepository extends JpaRepository<Offer, UUID> {

    Page<Offer> findByBusinessIdOrderByCreatedAtDesc(UUID businessId, Pageable pageable);

    /** Business-profile banner / "does this business currently have an active offer" — small result set, no paging needed. */
    List<Offer> findByBusinessIdAndStatusAndValidUntilAfterOrderByValidUntilAsc(UUID businessId, OfferStatus status, Instant now);

    /** Menu-item price overlay — see CatalogService#menu. Filtered further in Java via Offer#isCurrentlyActive, which already lives on the entity. */
    List<Offer> findByBusinessIdAndMenuItemIdIsNotNull(UUID businessId);

    /** Admin approval queue. */
    Page<Offer> findByStatusOrderByCreatedAtAsc(OfferStatus status, Pageable pageable);

    /**
     * The public feed — category/area/availability are optional filters over one query (mirrors
     * CommunityPostRepository's "(:param IS NULL OR ...)" convention for scalar params).
     * availabilityValues is always non-null/non-empty (the service computes {ONLINE,IN_STORE,BOTH}
     * when no specific filter is requested) — a null *collection* bound to IN is the one thing
     * this codebase's repositories deliberately avoid.
     *
     * "Ending soon" is a SEPARATE method ({@link #findActiveFeedEndingBefore}) rather than a
     * fourth "(:endingBefore IS NULL OR ...)" branch here — with a null Instant, Postgres's JDBC
     * driver can't determine that parameter's type ("could not determine data type of parameter")
     * once it sits after the `IN :availabilityValues` collection expansion, confirmed via a live
     * 500 on GET /offers during verification. Splitting into two distinct finder methods (this
     * codebase's own established convention for optional-filter combinations) sidesteps the
     * ambiguous-null-parameter case entirely instead of coercing/casting around it.
     */
    @Query("""
            SELECT o FROM Offer o JOIN Business b ON o.businessId = b.id
            WHERE o.status = :status AND o.validUntil > :now
              AND (:categoryId IS NULL OR b.category.id = :categoryId)
              AND (:areaId IS NULL OR b.area.id = :areaId)
              AND o.availability IN :availabilityValues
            ORDER BY o.validUntil ASC
            """)
    Page<Offer> findActiveFeed(@Param("status") OfferStatus status, @Param("now") Instant now,
                                @Param("categoryId") UUID categoryId, @Param("areaId") UUID areaId,
                                @Param("availabilityValues") Set<OfferAvailability> availabilityValues,
                                Pageable pageable);

    /** Same as {@link #findActiveFeed} plus a required (never-null) "ending soon" cutoff — see that method's doc comment. */
    @Query("""
            SELECT o FROM Offer o JOIN Business b ON o.businessId = b.id
            WHERE o.status = :status AND o.validUntil > :now AND o.validUntil <= :endingBefore
              AND (:categoryId IS NULL OR b.category.id = :categoryId)
              AND (:areaId IS NULL OR b.area.id = :areaId)
              AND o.availability IN :availabilityValues
            ORDER BY o.validUntil ASC
            """)
    Page<Offer> findActiveFeedEndingBefore(@Param("status") OfferStatus status, @Param("now") Instant now,
                                            @Param("categoryId") UUID categoryId, @Param("areaId") UUID areaId,
                                            @Param("availabilityValues") Set<OfferAvailability> availabilityValues,
                                            @Param("endingBefore") Instant endingBefore, Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE Offer o SET o.viewCount = o.viewCount + :delta WHERE o.id = :id")
    void adjustViewCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE Offer o SET o.claimCount = o.claimCount + :delta WHERE o.id = :id")
    void adjustClaimCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE Offer o SET o.redemptionCount = o.redemptionCount + :delta WHERE o.id = :id")
    void adjustRedemptionCount(@Param("id") UUID id, @Param("delta") int delta);

    /** Admin/report-driven takedown — same "any status transition is just a status write" shape as owner cancel. */
    @Modifying
    @Transactional
    @Query("UPDATE Offer o SET o.status = 'CANCELLED' WHERE o.id = :id")
    void cancel(@Param("id") UUID id);
}
