package com.bdreview.platform.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessRepository extends JpaRepository<Business, UUID> {

    Optional<Business> findBySlugAndDeletedAtIsNull(String slug);

    boolean existsBySlugAndDeletedAtIsNull(String slug);

    /** Every live branch of a brand — backs GET /api/v1/brands/{slug}/branches. */
    List<Business> findByBrandIdAndDeletedAtIsNull(UUID brandId);

    // -----------------------------------------------------------------
    // §2 Business Claim Flow — free-text "is my business already listed"
    // search, surfaced before the "add a business" form is filled in.
    // word_similarity() (pg_trgm) scores a name against the best-matching
    // word-boundary substring of the query, so extra words the owner types
    // (city/area) don't dilute the name match the way plain similarity()
    // would; a mention of the business's own area/city/category anywhere
    // in the query adds a modest ranking boost on top of the name score.
    // -----------------------------------------------------------------
    @Query(value = """
            SELECT b.* FROM business b
            JOIN area a ON a.id = b.area_id
            JOIN city c ON c.id = b.city_id
            JOIN category cat ON cat.id = b.category_id
            WHERE b.deleted_at IS NULL
              AND word_similarity(b.name, :query) > 0.3
            ORDER BY
              (word_similarity(b.name, :query)
               + CASE WHEN :query ILIKE '%' || a.name || '%' THEN 0.15 ELSE 0 END
               + CASE WHEN :query ILIKE '%' || c.name || '%' THEN 0.08 ELSE 0 END
               + CASE WHEN :query ILIKE '%' || cat.name || '%' THEN 0.05 ELSE 0 END
              ) DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Business> searchForClaim(@Param("query") String query);

    /**
     * Hard guard at listing-creation time (searchForClaim above is only a pre-step suggestion the
     * owner can click past) — an exact phone match is a near-certain duplicate regardless of name;
     * a close name match in the same area is treated the same way rather than a global name match,
     * since a name like "KFC" is legitimately repeated across many unrelated real businesses/areas.
     */
    @Query(value = """
            SELECT b.* FROM business b
            WHERE b.deleted_at IS NULL
              AND (
                b.contact_number = :phone
                OR (word_similarity(b.name, :name) > 0.5 AND b.area_id = :areaId)
              )
            LIMIT 5
            """, nativeQuery = true)
    List<Business> findLikelyDuplicates(@Param("name") String name, @Param("areaId") UUID areaId, @Param("phone") String phone);

    @Query(value = """
            SELECT levenshtein(b.name, :name) FROM business b WHERE b.id = :businessId
            """, nativeQuery = true)
    int levenshteinDistanceTo(@Param("businessId") UUID businessId, @Param("name") String name);

    // -----------------------------------------------------------------
    // §3 Search + Filter — combinable filters, GPS "near me" via PostGIS
    // ST_DWithin/ST_Distance against the GiST-indexed geography column.
    // Null parameters are treated as "no filter" (see COALESCE guards).
    // Sort options: relevance | rating | distance | newest | most_reviewed |
    // most_loved | trending. most_loved/trending are homepage-carousel-only
    // (not exposed in the manual filter UI) — trending counts business_reaction_event
    // rows (V37) in the last 7 days, an append-only log kept separate from the
    // toggled/deletable business_reaction table so a "how much activity this
    // week" query is actually answerable; see BusinessService#react.
    //
    // `q`/`location` are the free-text search-bar fields (spec: one search
    // box + one location box, no forced category/city/area dropdowns).
    // Matching reuses the same word_similarity() technique as
    // searchForClaim above (best-matching word-boundary substring, so
    // "Biriyani House Mirpur" still matches a business named "Biriyani
    // House" without the trailing location words diluting the score);
    // `location` additionally matches either direction (typed text inside
    // the area/city name, or vice versa) so "Mirpur" matches "Mirpur" and
    // "Dhaka" loosely matches longer city names alike. Filtering and
    // ranking both stay no-ops when q/location are blank, so plain
    // category/area/price/rating browsing (CategoriesGrid, ExploreCities,
    // the secondary filter row) is unaffected.
    // -----------------------------------------------------------------
    @Query(value = """
            SELECT b.*,
                   ST_Distance(b.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_m
            FROM business b
            JOIN area a ON a.id = b.area_id
            JOIN city c ON c.id = b.city_id
            JOIN category cat ON cat.id = b.category_id
            WHERE b.deleted_at IS NULL
              AND (:categoryId IS NULL OR b.category_id = :categoryId)
              AND (:areaId IS NULL OR b.area_id = :areaId)
              AND (:priceTier IS NULL OR b.price_tier = :priceTier)
              AND (:minRating IS NULL OR b.average_rating >= :minRating)
              AND (:lat IS NULL OR :lng IS NULL OR :radiusMeters IS NULL
                   OR ST_DWithin(b.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters))
              AND (:q IS NULL OR :q = ''
                   OR word_similarity(b.name, :q) > 0.25
                   OR word_similarity(cat.name, :q) > 0.4
                   OR :q ILIKE '%' || cat.name || '%'
                   OR :q ILIKE '%' || a.name || '%'
                   OR :q ILIKE '%' || c.name || '%')
              AND (:location IS NULL OR :location = ''
                   OR :location ILIKE '%' || a.name || '%' OR a.name ILIKE '%' || :location || '%'
                   OR :location ILIKE '%' || c.name || '%' OR c.name ILIKE '%' || :location || '%')
            ORDER BY
              CASE WHEN :sort = 'relevance' THEN
                (GREATEST(word_similarity(b.name, :q), similarity(b.name, :q)) * 2.0
                 + GREATEST(word_similarity(cat.name, :q), similarity(cat.name, :q)) * 1.0
                 + CASE WHEN :q <> '' AND :q ILIKE '%' || a.name || '%' THEN 0.4 ELSE 0 END
                 + CASE WHEN :q <> '' AND :q ILIKE '%' || c.name || '%' THEN 0.2 ELSE 0 END
                 + CASE WHEN :location <> ''
                        AND (:location ILIKE '%' || a.name || '%' OR a.name ILIKE '%' || :location || '%')
                        THEN 0.5 ELSE 0 END
                 + CASE WHEN :location <> ''
                        AND (:location ILIKE '%' || c.name || '%' OR c.name ILIKE '%' || :location || '%')
                        THEN 0.3 ELSE 0 END
                 + (b.average_rating / 20.0)
                ) END DESC NULLS LAST,
              CASE WHEN :sort = 'rating'        THEN b.average_rating END DESC NULLS LAST,
              CASE WHEN :sort = 'most_reviewed'  THEN b.review_count END DESC NULLS LAST,
              CASE WHEN :sort = 'newest'         THEN b.created_at END DESC NULLS LAST,
              CASE WHEN :sort = 'most_loved'     THEN b.total_love_count END DESC NULLS LAST,
              CASE WHEN :sort = 'trending' THEN
                (SELECT count(*) FROM business_reaction_event bre
                  WHERE bre.business_id = b.id AND bre.created_at >= now() - interval '7 days')
              END DESC NULLS LAST,
              CASE WHEN :sort = 'distance' AND :lat IS NOT NULL
                   THEN ST_Distance(b.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) END ASC NULLS LAST
            """,
            countQuery = """
            SELECT count(*)
            FROM business b
            JOIN area a ON a.id = b.area_id
            JOIN city c ON c.id = b.city_id
            JOIN category cat ON cat.id = b.category_id
            WHERE b.deleted_at IS NULL
              AND (:categoryId IS NULL OR b.category_id = :categoryId)
              AND (:areaId IS NULL OR b.area_id = :areaId)
              AND (:priceTier IS NULL OR b.price_tier = :priceTier)
              AND (:minRating IS NULL OR b.average_rating >= :minRating)
              AND (:lat IS NULL OR :lng IS NULL OR :radiusMeters IS NULL
                   OR ST_DWithin(b.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters))
              AND (:q IS NULL OR :q = ''
                   OR word_similarity(b.name, :q) > 0.25
                   OR word_similarity(cat.name, :q) > 0.4
                   OR :q ILIKE '%' || cat.name || '%'
                   OR :q ILIKE '%' || a.name || '%'
                   OR :q ILIKE '%' || c.name || '%')
              AND (:location IS NULL OR :location = ''
                   OR :location ILIKE '%' || a.name || '%' OR a.name ILIKE '%' || :location || '%'
                   OR :location ILIKE '%' || c.name || '%' OR c.name ILIKE '%' || :location || '%')
            """,
            nativeQuery = true)
    Page<Business> search(@Param("categoryId") UUID categoryId,
                          @Param("areaId") UUID areaId,
                          @Param("priceTier") String priceTier,
                          @Param("minRating") Double minRating,
                          @Param("lat") Double lat,
                          @Param("lng") Double lng,
                          @Param("radiusMeters") Double radiusMeters,
                          @Param("q") String q,
                          @Param("location") String location,
                          @Param("sort") String sort,
                          Pageable pageable);

    /** Filters additionally by required business_attribute ids (owner-declared amenities, spec §1/§3). */
    @Query(value = """
            SELECT b.* FROM business b
            WHERE b.deleted_at IS NULL
              AND b.id IN (
                  SELECT baa.business_id FROM business_attribute_assignment baa
                  WHERE baa.attribute_id IN (:attributeIds)
                  GROUP BY baa.business_id
                  HAVING COUNT(DISTINCT baa.attribute_id) = :attributeCount
              )
            """, nativeQuery = true)
    Page<Business> filterByAllAttributes(@Param("attributeIds") List<UUID> attributeIds,
                                         @Param("attributeCount") long attributeCount,
                                         Pageable pageable);

    // -----------------------------------------------------------------
    // §1 Rating aggregates — atomic SQL increments only, never
    // read-modify-write, so concurrent review writes cannot collide with
    // the entity's optimistic-lock `version`. `ratingDelta`/`countDelta`
    // may be negative (review edited down, or hidden/soft-deleted).
    // -----------------------------------------------------------------
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE business
            SET rating_sum = rating_sum + :ratingDelta,
                review_count = review_count + :countDelta,
                average_rating = CASE
                    WHEN review_count + :countDelta <= 0 THEN 0
                    ELSE ROUND((rating_sum + :ratingDelta)::numeric / (review_count + :countDelta), 2)
                END,
                updated_at = now()
            WHERE id = :businessId
            """, nativeQuery = true)
    void applyRatingAggregateDelta(@Param("businessId") UUID businessId,
                                   @Param("ratingDelta") int ratingDelta,
                                   @Param("countDelta") int countDelta);

    // -----------------------------------------------------------------
    // §1 Soft delete — row stays archived for admin/dispute access.
    // -----------------------------------------------------------------
    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.deletedAt = CURRENT_TIMESTAMP WHERE b.id = :id AND b.deletedAt IS NULL")
    int softDelete(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.ownerUserId = :newOwnerUserId WHERE b.id = :id")
    void updateOwner(@Param("id") UUID id, @Param("newOwnerUserId") UUID newOwnerUserId);

    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.verified = true WHERE b.id = :id")
    void markVerified(@Param("id") UUID id);

    /** Report workflow: a LISTING report resolved ACTION_TAKEN flags the business with its reason. */
    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.flagged = true, b.flagReason = :reason, b.flaggedAt = :now WHERE b.id = :id")
    void flag(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.flagged = false, b.flagReason = NULL, b.flaggedAt = NULL WHERE b.id = :id")
    void unflag(@Param("id") UUID id);

    // -----------------------------------------------------------------
    // Business-level reactions (Like/Dislike/Love/Wow) — same atomic-increment
    // convention as applyRatingAggregateDelta above; see BusinessService#react.
    // -----------------------------------------------------------------
    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.totalLikeCount = b.totalLikeCount + :delta WHERE b.id = :id")
    void adjustLikeCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.totalDislikeCount = b.totalDislikeCount + :delta WHERE b.id = :id")
    void adjustDislikeCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.totalLoveCount = b.totalLoveCount + :delta WHERE b.id = :id")
    void adjustLoveCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.totalWowCount = b.totalWowCount + :delta WHERE b.id = :id")
    void adjustWowCount(@Param("id") UUID id, @Param("delta") int delta);

    List<Business> findByOwnerUserIdAndDeletedAtIsNull(UUID ownerUserId);

    /**
     * Batched lookup for cross-business listings (home page "Recent Activity" feed) —
     * JOIN FETCH avoids LazyInitializationException once the session closes
     * (open-in-view is false), same reasoning as findByIdWithDetails below.
     */
    @Query("""
            SELECT b FROM Business b
            JOIN FETCH b.category
            WHERE b.id IN :ids AND b.deletedAt IS NULL
            """)
    List<Business> findAllByIdInWithCategory(@Param("ids") Collection<UUID> ids);

    /** Smart search's second step (ranked ids → cards): category/city/area fetched up front, no per-row lazy loads. */
    @Query("""
            SELECT b FROM Business b
            JOIN FETCH b.category
            JOIN FETCH b.city
            JOIN FETCH b.area
            WHERE b.id IN :ids AND b.deletedAt IS NULL
            """)
    List<Business> findAllByIdInWithPlace(@Param("ids") Collection<UUID> ids);

    // -----------------------------------------------------------------
    // Admin panel (com.bdreview.platform.admin) — listing/search across
    // ALL businesses (including soft-deleted, so admins can restore them),
    // plus the verified-toggle and undelete actions the public API never
    // needed. The consumer-facing search()/filter methods above are
    // untouched.
    // -----------------------------------------------------------------
    // JOIN FETCH is required here: spring.jpa.open-in-view is false in this
    // project, so the Hibernate session is closed before Thymeleaf renders
    // the admin list page — without an eager fetch, b.category/b.city
    // (LAZY by default) throw LazyInitializationException at render time.
    // countQuery is given explicitly since Spring Data can't safely derive
    // a count query from a query containing JOIN FETCH.
    @Query(value = """
            SELECT b FROM Business b
            JOIN FETCH b.category
            JOIN FETCH b.city
            JOIN FETCH b.area
            WHERE (:query IS NULL OR :query = '' OR LOWER(b.name) LIKE LOWER(CONCAT('%', :query, '%')))
              AND (:categoryId IS NULL OR b.category.id = :categoryId)
              AND (:cityId IS NULL OR b.city.id = :cityId)
              AND (:includeDeleted = true OR b.deletedAt IS NULL)
              AND (:unclaimedOnly = false OR b.ownerUserId IN (
                  SELECT u.id FROM User u WHERE u.role = com.bdreview.platform.auth.UserRole.ADMIN))
            """,
            countQuery = """
            SELECT count(b) FROM Business b
            WHERE (:query IS NULL OR :query = '' OR LOWER(b.name) LIKE LOWER(CONCAT('%', :query, '%')))
              AND (:categoryId IS NULL OR b.category.id = :categoryId)
              AND (:cityId IS NULL OR b.city.id = :cityId)
              AND (:includeDeleted = true OR b.deletedAt IS NULL)
              AND (:unclaimedOnly = false OR b.ownerUserId IN (
                  SELECT u.id FROM User u WHERE u.role = com.bdreview.platform.auth.UserRole.ADMIN))
            """)
    Page<Business> adminSearch(@Param("query") String query,
                               @Param("categoryId") UUID categoryId,
                               @Param("cityId") UUID cityId,
                               @Param("includeDeleted") boolean includeDeleted,
                               @Param("unclaimedOnly") boolean unclaimedOnly,
                               Pageable pageable);

    /** Dashboard stat tile: admin-seeded listings still awaiting a real owner claim — see BusinessClaimService#ensureClaimable. */
    @Query("""
            SELECT count(b) FROM Business b
            WHERE b.deletedAt IS NULL
              AND b.ownerUserId IN (SELECT u.id FROM User u WHERE u.role = com.bdreview.platform.auth.UserRole.ADMIN)
            """)
    long countUnclaimed();

    // Same reasoning as adminSearch above — the business detail/edit pages
    // read category/city/area/attributes, all LAZY, after the session closes.
    @Query("""
            SELECT b FROM Business b
            JOIN FETCH b.category
            JOIN FETCH b.city
            JOIN FETCH b.area
            LEFT JOIN FETCH b.attributes
            WHERE b.id = :id
            """)
    Optional<Business> findByIdWithDetails(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.deletedAt = NULL WHERE b.id = :id")
    void restore(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("UPDATE Business b SET b.verified = :verified WHERE b.id = :id")
    void setVerified(@Param("id") UUID id, @Param("verified") boolean verified);
}