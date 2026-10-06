package com.bdreview.platform.listing;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Duplicate listings (V65): candidate pairs by name similarity (pg_trgm) that are either within
 * {@value #MAX_DISTANCE_M} m of each other or share a phone number. Admins merge a pair (everything
 * public moves to the kept listing, the old slug keeps resolving via {@code business_slug_redirect})
 * or dismiss it as "not a duplicate". Branches of one brand are never offered as duplicates.
 */
@Service
public class DuplicateService {

    public static final int MAX_DISTANCE_M = 300;
    public static final double MIN_NAME_SIMILARITY = 0.4;

    private final JdbcTemplate jdbc;
    private final AuditLogService auditLogService;

    public DuplicateService(JdbcTemplate jdbc, AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.auditLogService = auditLogService;
    }

    public List<Map<String, Object>> candidates(int limit) {
        // Two index-friendly candidate sets (nearby via the GiST location index, same phone via
        // equality) instead of one OR-ed self-join, which degrades to comparing every pair.
        return jdbc.queryForList("""
                WITH pairs AS (
                    SELECT a.id AS a_id, b.id AS b_id
                    FROM business a JOIN business b
                      ON a.id < b.id AND ST_DWithin(a.location, b.location, ?)
                    WHERE a.deleted_at IS NULL AND b.deleted_at IS NULL
                    UNION
                    SELECT a.id, b.id
                    FROM business a JOIN business b
                      ON a.id < b.id AND a.contact_number = b.contact_number
                    WHERE a.deleted_at IS NULL AND b.deleted_at IS NULL
                )
                SELECT a.id AS a_id, a.name AS a_name, a.slug AS a_slug, a.review_count AS a_reviews, a.verified AS a_verified,
                       a.contact_number AS a_phone, a.created_at AS a_created,
                       b.id AS b_id, b.name AS b_name, b.slug AS b_slug, b.review_count AS b_reviews, b.verified AS b_verified,
                       b.contact_number AS b_phone, b.created_at AS b_created,
                       round(similarity(a.name, b.name)::numeric, 2) AS name_similarity,
                       round(ST_Distance(a.location, b.location)::numeric) AS distance_m,
                       (a.contact_number = b.contact_number) AS same_phone,
                       aa.name AS a_area, ba.name AS b_area
                FROM pairs p
                JOIN business a ON a.id = p.a_id
                JOIN business b ON b.id = p.b_id
                JOIN area aa ON aa.id = a.area_id
                JOIN area ba ON ba.id = b.area_id
                WHERE similarity(a.name, b.name) >= ?
                  AND (a.brand_id IS NULL OR b.brand_id IS NULL OR a.brand_id <> b.brand_id)
                  AND NOT EXISTS (SELECT 1 FROM business_duplicate_dismissal d
                                  WHERE d.business_a = a.id AND d.business_b = b.id)
                ORDER BY same_phone DESC, name_similarity DESC, distance_m ASC
                LIMIT ?
                """, MAX_DISTANCE_M, MIN_NAME_SIMILARITY, limit);
    }

    @Transactional
    public void dismiss(UUID first, UUID second, String reason) {
        String why = VerificationService.requireReason(reason);
        if (first.equals(second)) {
            throw new BadRequestException("Pick two different listings.");
        }
        // Postgres orders uuids bytewise (unsigned) — Java's UUID.compareTo is signed and can disagree,
        // so the pair is ordered in SQL to match the finder's a.id < b.id and the table's CHECK.
        jdbc.update("""
                INSERT INTO business_duplicate_dismissal (business_a, business_b, created_by, reason)
                VALUES (LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid), ?, ?)
                ON CONFLICT DO NOTHING
                """, first, second, first, second, CurrentUser.idOrNull(), why);
        auditLogService.record("BUSINESS", first, "DUPLICATE_DISMISSED", why, null,
                Map.of("businessA", first.toString(), "businessB", second.toString()));
    }

    /**
     * Merges {@code removeId} into {@code keepId}: moves reviews, gallery photos, offers, menu items,
     * bookmarks (followers) and reactions, recomputes the kept listing's rating/reaction totals,
     * soft-deletes the old listing and redirects its slug. Rows that would collide with the kept
     * listing (the same user reviewed / bookmarked / reacted to both) stay on the archived listing.
     * Orders, bookings and claims stay on the old listing as history.
     */
    @Transactional
    public Map<String, Object> merge(UUID keepId, UUID removeId, String reason) {
        String why = VerificationService.requireReason(reason);
        if (keepId.equals(removeId)) {
            throw new BadRequestException("Pick two different listings.");
        }
        Map<String, Object> keep = live(keepId);
        Map<String, Object> remove = live(removeId);

        Map<String, Object> moved = new LinkedHashMap<>();
        moved.put("reviews", jdbc.update("""
                UPDATE review r SET business_id = ? WHERE r.business_id = ?
                  AND NOT (r.deleted_at IS NULL AND EXISTS (SELECT 1 FROM review k
                       WHERE k.business_id = ? AND k.user_id = r.user_id AND k.deleted_at IS NULL))
                """, keepId, removeId, keepId));
        moved.put("photos", jdbc.update("""
                UPDATE business_photo SET business_id = ?,
                       sort_order = sort_order + (SELECT coalesce(max(sort_order) + 1, 0) FROM business_photo WHERE business_id = ?)
                WHERE business_id = ?
                """, keepId, keepId, removeId));
        jdbc.update("UPDATE photo_moderation SET source_id = ? WHERE source_type = 'BUSINESS_PHOTO' AND source_id = ?", keepId, removeId);
        jdbc.update("UPDATE photo_moderation SET business_id = ? WHERE business_id = ?", keepId, removeId);
        moved.put("offers", jdbc.update("UPDATE offer SET business_id = ? WHERE business_id = ?", keepId, removeId));
        moved.put("menuItems", jdbc.update("""
                UPDATE business_menu_item SET business_id = ?,
                       sort_order = sort_order + (SELECT coalesce(max(sort_order) + 1, 0) FROM business_menu_item WHERE business_id = ?)
                WHERE business_id = ?
                """, keepId, keepId, removeId));
        moved.put("followers", jdbc.update("""
                UPDATE bookmark bm SET business_id = ? WHERE bm.business_id = ?
                  AND NOT EXISTS (SELECT 1 FROM bookmark k WHERE k.business_id = ? AND k.user_id = bm.user_id)
                """, keepId, removeId, keepId));
        moved.put("reactions", jdbc.update("""
                UPDATE business_reaction br SET business_id = ? WHERE br.business_id = ?
                  AND NOT EXISTS (SELECT 1 FROM business_reaction k WHERE k.business_id = ? AND k.user_id = br.user_id
                                  AND k.reaction_type = br.reaction_type)
                """, keepId, removeId, keepId));
        jdbc.update("UPDATE business_reaction_event SET business_id = ? WHERE business_id = ?", keepId, removeId);

        // Recompute the kept listing's aggregates from the rows it now has (only RECOMMENDED,
        // live reviews count — same rule as applyRatingAggregateDelta's callers).
        jdbc.update("""
                UPDATE business b SET
                    review_count = s.cnt, rating_sum = s.total,
                    average_rating = CASE WHEN s.cnt = 0 THEN 0 ELSE round(s.total::numeric / s.cnt, 2) END,
                    total_like_count = (SELECT count(*) FROM business_reaction WHERE business_id = b.id AND reaction_type = 'LIKE'),
                    total_dislike_count = (SELECT count(*) FROM business_reaction WHERE business_id = b.id AND reaction_type = 'DISLIKE'),
                    total_love_count = (SELECT count(*) FROM business_reaction WHERE business_id = b.id AND reaction_type = 'LOVE'),
                    total_wow_count = (SELECT count(*) FROM business_reaction WHERE business_id = b.id AND reaction_type = 'WOW'),
                    updated_at = now()
                FROM (SELECT count(*) AS cnt, coalesce(sum(rating), 0) AS total FROM review
                      WHERE business_id = ? AND deleted_at IS NULL AND visibility_status = 'RECOMMENDED') s
                WHERE b.id = ?
                """, keepId, keepId);
        jdbc.update("""
                UPDATE business b SET
                    review_count = s.cnt, rating_sum = s.total,
                    average_rating = CASE WHEN s.cnt = 0 THEN 0 ELSE round(s.total::numeric / s.cnt, 2) END,
                    deleted_at = now(), updated_at = now()
                FROM (SELECT count(*) AS cnt, coalesce(sum(rating), 0) AS total FROM review
                      WHERE business_id = ? AND deleted_at IS NULL AND visibility_status = 'RECOMMENDED') s
                WHERE b.id = ?
                """, removeId, removeId);

        // Old URLs keep working: the removed slug, and any slug that already redirected to it.
        jdbc.update("UPDATE business_slug_redirect SET business_id = ? WHERE business_id = ?", keepId, removeId);
        jdbc.update("""
                INSERT INTO business_slug_redirect (old_slug, business_id) VALUES (?, ?)
                ON CONFLICT (old_slug) DO UPDATE SET business_id = EXCLUDED.business_id
                """, remove.get("slug"), keepId);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("kept", keep);
        before.put("removed", remove);
        Map<String, Object> after = new LinkedHashMap<>(moved);
        after.put("keptBusinessId", keepId.toString());
        after.put("redirectedSlug", remove.get("slug"));
        auditLogService.record("BUSINESS", keepId, "BUSINESS_MERGED", why, before, after);
        auditLogService.record("BUSINESS", removeId, "BUSINESS_MERGED_AWAY", why, remove,
                Map.of("mergedInto", keepId.toString()));
        return moved;
    }

    /** The live business a merged-away slug now points to. */
    public Optional<String> redirectTarget(String slug) {
        return jdbc.queryForList("""
                SELECT b.slug FROM business_slug_redirect r JOIN business b ON b.id = r.business_id
                WHERE r.old_slug = ? AND b.deleted_at IS NULL
                """, String.class, slug).stream().findFirst();
    }

    private Map<String, Object> live(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id::text AS id, name, slug, review_count FROM business WHERE id = ? AND deleted_at IS NULL", id);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Business not found (or already merged/deleted)");
        }
        return new LinkedHashMap<>(rows.get(0));
    }
}
