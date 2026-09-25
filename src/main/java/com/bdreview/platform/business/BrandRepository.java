package com.bdreview.platform.business;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrandRepository extends JpaRepository<Brand, UUID> {

    Optional<Brand> findBySlugAndDeletedAtIsNull(String slug);

    Optional<Brand> findByNameIgnoreCaseAndDeletedAtIsNull(String name);

    boolean existsBySlugAndDeletedAtIsNull(String slug);

    /** Admin reference-data "Brands" tab. */
    List<Brand> findByDeletedAtIsNull(Sort sort);

    List<Brand> findByIdInAndDeletedAtIsNull(Collection<UUID> ids);

    /**
     * Rating rollup — computed on read, not an atomic delta (see Business#brandId's
     * javadoc). Batched: called once per search/detail response with every distinct
     * brand_id on the page, same convention as BusinessService's galleryUrlsByBusiness
     * / claimedByOwner batched lookups.
     */
    @Query(value = """
            SELECT b.brand_id AS brandId, COUNT(*) AS branchCount,
                   COALESCE(SUM(b.rating_sum),0) AS ratingSum,
                   COALESCE(SUM(b.review_count),0) AS reviewCount
            FROM business b
            WHERE b.brand_id IN (:brandIds) AND b.deleted_at IS NULL
            GROUP BY b.brand_id
            """, nativeQuery = true)
    List<BrandAggregateRow> aggregatesFor(@Param("brandIds") Collection<UUID> brandIds);
}
