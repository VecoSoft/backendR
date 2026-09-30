package com.bdreview.platform.search;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The one ranked query behind smart search. Every value is a bound parameter — term lists travel
 * as a single '|'-joined string split server-side with string_to_array, and {@link SearchText}
 * has already reduced every term to letters/digits/spaces, so neither '|' nor LIKE wildcards can
 * appear inside a term.
 *
 * <h2>Scoring (explainable by design — each component also becomes a visible match reason)</h2>
 * <pre>
 *   name contains a term (or fuzzy ≥ 0.6)   3.0 × similarity
 *   a menu item / service matches          2.0 each
 *   description mentions a term            0.75
 *   category kind matches                  1.5
 *   distance (when an origin is known)     1.5 / (1 + km)
 *   rating, shrunk toward 3.5 by 3 votes   ratingWeight × bayes/5   (0.6, or 1.5 for "best/good")
 *   review volume                          0.3 × min(ln(1+n)/ln(51), 1)
 *   verified                               0.3
 *   profile completeness                   0.1 description + 0.1 cover photo
 *   preferred price tier (soft)            0.5
 * </pre>
 * Text components only count toward matching when requireMatch is on; everything else is ranking.
 */
@Repository
public class SmartSearchRepository {

    /** Words below this similarity are not considered a name match (trigram word_similarity). */
    static final double NAME_MATCH_THRESHOLD = 0.6;

    private static final String SQL = """
            WITH q AS (
                SELECT string_to_array(NULLIF(:likeTerms, ''), '|') AS like_terms,
                       string_to_array(NULLIF(:wordTerms, ''), '|') AS word_terms,
                       string_to_array(NULLIF(:kinds, ''), '|')     AS kinds,
                       string_to_array(NULLIF(:tiers, ''), '|')     AS tiers,
                       string_to_array(NULLIF(:softTiers, ''), '|') AS soft_tiers,
                       CASE WHEN CAST(:lat AS double precision) IS NULL THEN NULL
                            ELSE ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision),
                                                         CAST(:lat AS double precision)), 4326)::geography
                       END AS origin
            ),
            candidates AS (
                SELECT b.id, b.name, b.description, b.location, b.average_rating, b.review_count, b.verified,
                       b.cover_photo_url, b.price_tier, b.created_at, cat.kind,
                       q.like_terms, q.word_terms, q.kinds, q.soft_tiers, q.origin
                FROM business b
                JOIN category cat ON cat.id = b.category_id
                CROSS JOIN q
                WHERE b.deleted_at IS NULL
                  AND (CAST(:categoryId AS uuid) IS NULL OR b.category_id = CAST(:categoryId AS uuid))
                  AND (CAST(:areaId AS uuid) IS NULL OR b.area_id = CAST(:areaId AS uuid))
                  AND (CAST(:cityId AS uuid) IS NULL OR b.city_id = CAST(:cityId AS uuid))
                  AND (q.tiers IS NULL OR b.price_tier = ANY(q.tiers))
                  AND (CAST(:minRating AS numeric) IS NULL OR b.average_rating >= CAST(:minRating AS numeric))
                  AND (CAST(:radius AS double precision) IS NULL OR q.origin IS NULL
                       OR ST_DWithin(b.location, q.origin, CAST(:radius AS double precision)))
                  AND (NOT :openNow OR (
                        EXISTS (SELECT 1 FROM business_operating_hours h
                                WHERE h.business_id = b.id AND NOT h.closed
                                  AND h.open_time IS NOT NULL AND h.close_time IS NOT NULL
                                  AND ((h.day_of_week = :dow AND (
                                          (h.open_time <= h.close_time AND CAST(:nowTime AS time) >= h.open_time
                                                                       AND CAST(:nowTime AS time) < h.close_time)
                                       OR (h.open_time > h.close_time AND CAST(:nowTime AS time) >= h.open_time)))
                                    OR (h.day_of_week = :prevDow AND h.open_time > h.close_time
                                        AND CAST(:nowTime AS time) < h.close_time)))
                        AND NOT EXISTS (SELECT 1 FROM business_hours_exception e
                                        WHERE e.business_id = b.id AND e.closed
                                          AND CAST(:today AS date) BETWEEN e.start_date AND e.end_date)))
            ),
            -- Menu/service matches are found term-first (one trigram-index probe per term, see V57)
            -- rather than business-first, since these tables grow far faster than business itself.
            menu_hits AS (
                SELECT DISTINCT ON (m.business_id) m.business_id, m.name
                FROM q
                CROSS JOIN LATERAL (
                    SELECT t, false AS whole_word FROM unnest(q.like_terms) t
                    UNION ALL
                    SELECT t, true FROM unnest(q.word_terms) t) terms
                JOIN business_menu_item m
                  ON (NOT terms.whole_word AND m.name ILIKE '%' || terms.t || '%')
                  OR (terms.whole_word AND m.name ~* ('\\m' || terms.t || '\\M'))
                ORDER BY m.business_id, m.is_popular DESC, m.sort_order
            ),
            service_hits AS (
                SELECT DISTINCT ON (s.business_id) s.business_id, s.name
                FROM q
                CROSS JOIN LATERAL (
                    SELECT t, false AS whole_word FROM unnest(q.like_terms) t
                    UNION ALL
                    SELECT t, true FROM unnest(q.word_terms) t) terms
                JOIN business_service s
                  ON (NOT terms.whole_word AND s.name ILIKE '%' || terms.t || '%')
                  OR (terms.whole_word AND s.name ~* ('\\m' || terms.t || '\\M'))
                ORDER BY s.business_id, s.sort_order
            ),
            signals AS (
                SELECT c.*,
                       GREATEST(
                         COALESCE((SELECT max(CASE WHEN c.name ILIKE '%' || t || '%' THEN 1.0
                                                   ELSE word_similarity(t, c.name) END)
                                   FROM unnest(c.like_terms) t), 0),
                         COALESCE((SELECT 1.0 FROM unnest(c.word_terms) t
                                   WHERE c.name ~* ('\\m' || t || '\\M') LIMIT 1), 0)
                       ) AS name_score,
                       COALESCE(c.kind = ANY(c.kinds), false) AS kind_match,
                       mh.name AS menu_hit,
                       sh.name AS service_hit,
                       COALESCE(c.description IS NOT NULL AND (
                           EXISTS (SELECT 1 FROM unnest(c.like_terms) t WHERE c.description ILIKE '%' || t || '%')
                           OR EXISTS (SELECT 1 FROM unnest(c.word_terms) t WHERE c.description ~* ('\\m' || t || '\\M'))),
                           false) AS desc_hit,
                       CASE WHEN c.origin IS NULL OR c.location IS NULL THEN NULL
                            ELSE ST_Distance(c.location, c.origin) END AS distance_m,
                       COALESCE(c.price_tier = ANY(c.soft_tiers), false) AS soft_price_match
                FROM candidates c
                LEFT JOIN menu_hits mh ON mh.business_id = c.id
                LEFT JOIN service_hits sh ON sh.business_id = c.id
            ),
            scored AS (
                SELECT s.*,
                       (CASE WHEN s.name_score >= :nameThreshold THEN 3.0 * s.name_score ELSE 0 END
                        + CASE WHEN s.menu_hit IS NOT NULL THEN 2.0 ELSE 0 END
                        + CASE WHEN s.service_hit IS NOT NULL THEN 2.0 ELSE 0 END
                        + CASE WHEN s.desc_hit THEN 0.75 ELSE 0 END
                        + CASE WHEN s.kind_match THEN 1.5 ELSE 0 END
                        + CASE WHEN s.distance_m IS NULL THEN 0 ELSE 1.5 / (1 + s.distance_m / 1000.0) END
                        + CAST(:ratingWeight AS double precision)
                          * ((s.average_rating * s.review_count + 3.5 * 3) / (s.review_count + 3)) / 5.0
                        + 0.3 * LEAST(ln(1 + s.review_count) / ln(51), 1)
                        + CASE WHEN s.verified THEN 0.3 ELSE 0 END
                        + CASE WHEN s.description IS NOT NULL AND s.description <> '' THEN 0.1 ELSE 0 END
                        + CASE WHEN s.cover_photo_url IS NOT NULL THEN 0.1 ELSE 0 END
                        + CASE WHEN s.soft_price_match THEN 0.5 ELSE 0 END
                       ) AS score
                FROM signals s
                WHERE NOT :requireMatch
                   OR s.name_score >= :nameThreshold
                   OR s.menu_hit IS NOT NULL
                   OR s.service_hit IS NOT NULL
                   OR s.desc_hit
                   OR s.kind_match
            )
            SELECT id, score, name_score, kind_match, menu_hit, service_hit, desc_hit, distance_m, soft_price_match,
                   count(*) OVER () AS total
            FROM scored
            ORDER BY
              CASE WHEN :sort = 'rating' THEN average_rating END DESC NULLS LAST,
              CASE WHEN :sort = 'most_reviewed' THEN review_count END DESC NULLS LAST,
              CASE WHEN :sort = 'distance' THEN distance_m END ASC NULLS LAST,
              score DESC, review_count DESC, id
            LIMIT :limit OFFSET :offset
            """;

    public record Criteria(List<String> likeTerms, List<String> wordTerms, List<String> kinds,
                           boolean requireMatch, double nameThreshold,
                           UUID categoryId, UUID areaId, UUID cityId,
                           List<String> tiers, List<String> softTiers, Double minRating,
                           Double originLat, Double originLng, Double radiusMeters,
                           boolean openNow, double ratingWeight, String sort) {
    }

    /** One ranked row; the business itself is loaded afterwards in one batch. */
    public record Hit(UUID id, double score, double nameScore, boolean kindMatch, String menuHit,
                      String serviceHit, boolean descHit, Double distanceMeters, boolean softPriceMatch) {
    }

    public record HitPage(List<Hit> hits, long total) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SmartSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public HitPage search(Criteria c, LocalDate today, LocalTime now, String dow, String prevDow,
                          int limit, int offset) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("likeTerms", String.join("|", c.likeTerms()))
                .addValue("wordTerms", String.join("|", c.wordTerms()))
                .addValue("kinds", String.join("|", c.kinds()))
                .addValue("tiers", String.join("|", c.tiers()))
                .addValue("softTiers", String.join("|", c.softTiers()))
                .addValue("requireMatch", c.requireMatch())
                .addValue("nameThreshold", c.nameThreshold())
                .addValue("categoryId", c.categoryId() == null ? null : c.categoryId().toString())
                .addValue("areaId", c.areaId() == null ? null : c.areaId().toString())
                .addValue("cityId", c.cityId() == null ? null : c.cityId().toString())
                .addValue("minRating", c.minRating())
                .addValue("lat", c.originLat())
                .addValue("lng", c.originLng())
                .addValue("radius", c.radiusMeters())
                .addValue("openNow", c.openNow())
                .addValue("dow", dow)
                .addValue("prevDow", prevDow)
                .addValue("nowTime", now.withNano(0).toString())
                .addValue("today", today.toString())
                .addValue("ratingWeight", c.ratingWeight())
                .addValue("sort", c.sort())
                .addValue("limit", limit)
                .addValue("offset", offset);
        long[] total = {0};
        List<Hit> hits = jdbc.query(SQL, p, (rs, n) -> {
            total[0] = rs.getLong("total");
            return new Hit(rs.getObject("id", UUID.class), rs.getDouble("score"), rs.getDouble("name_score"),
                    rs.getBoolean("kind_match"), rs.getString("menu_hit"), rs.getString("service_hit"),
                    rs.getBoolean("desc_hit"), (Double) rs.getObject("distance_m"), rs.getBoolean("soft_price_match"));
        });
        return new HitPage(hits, total[0]);
    }

    /** Suggestion dropdown: live business names starting with (or containing a word starting with) the prefix. */
    public List<NameHit> businessNamesLike(String prefix, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("prefix", prefix)
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT b.name, b.slug, a.name AS area_name
                FROM business b JOIN area a ON a.id = b.area_id
                WHERE b.deleted_at IS NULL
                  AND (b.name ILIKE :prefix || '%' OR b.name ILIKE '% ' || :prefix || '%'
                       OR (char_length(:prefix) >= 4 AND word_similarity(:prefix, b.name) >= 0.5))
                ORDER BY (b.name ILIKE :prefix || '%') DESC, word_similarity(:prefix, b.name) DESC, b.review_count DESC
                LIMIT :limit
                """, p, (rs, n) -> new NameHit(rs.getString("name"), rs.getString("slug"), rs.getString("area_name")));
    }

    public record NameHit(String name, String slug, String areaName) {
    }
}
