package com.bdreview.platform.community;

import com.bdreview.platform.business.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Second Spring Data repository over the existing {@code business} table,
 * used only for the "@mention a business" typeahead when composing a
 * community post. Kept as its own interface (instead of adding a method to
 * business.BusinessRepository) so no existing file needs to change.
 * Same word_similarity(pg_trgm) technique as BusinessRepository#searchForClaim.
 */
public interface CommunityBusinessMentionRepository extends JpaRepository<Business, java.util.UUID> {

    @Query(value = """
            SELECT b.* FROM business b
            WHERE b.deleted_at IS NULL
              AND word_similarity(b.name, :query) > 0.2
            ORDER BY word_similarity(b.name, :query) DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Business> searchForMention(@Param("query") String query);
}
