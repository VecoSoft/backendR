package com.bdreview.platform.listing;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog → Data checks (V65): live listings that look broken or abandoned, one query per check,
 * plus a bulk soft-delete (reason required, one audit entry per listing).
 */
@Service
public class DataCheckService {

    public enum Check {
        AREA_CITY_MISMATCH("Area not in its city", "The listing's area belongs to a different city than the listing."),
        MISSING_HOURS("Missing hours", "No opening hours text and no structured weekly hours."),
        MISSING_COORDINATES("Missing coordinates", "Map pin is unset (0, 0) — distance and near-me search can't work."),
        TEST_LIKE_NAME("Test-like name", "Name is 3 characters or fewer, looks like a test entry, or has no letters/vowels."),
        INACTIVE("No reviews, inactive 90 days", "Zero reviews and no update for 90 days.");

        private final String label;
        private final String description;

        Check(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }
    }

    private static final String SELECT = """
            SELECT b.id, b.name, b.slug, b.review_count, b.created_at, b.updated_at, b.verified,
                   c.name AS city_name, a.name AS area_name, ac.name AS area_city_name, cat.name AS category_name
            FROM business b
            JOIN city c ON c.id = b.city_id
            JOIN area a ON a.id = b.area_id
            JOIN city ac ON ac.id = a.city_id
            JOIN category cat ON cat.id = b.category_id
            WHERE b.deleted_at IS NULL AND\s""";

    private static final Map<Check, String> WHERE = Map.of(
            Check.AREA_CITY_MISMATCH, "a.city_id <> b.city_id",
            Check.MISSING_HOURS, """
                    (b.operating_hours IS NULL OR btrim(b.operating_hours) = '')
                    AND NOT EXISTS (SELECT 1 FROM business_operating_hours h WHERE h.business_id = b.id)""",
            Check.MISSING_COORDINATES, "(b.location IS NULL OR (ST_X(b.location::geometry) = 0 AND ST_Y(b.location::geometry) = 0))",
            Check.TEST_LIKE_NAME, """
                    (char_length(btrim(b.name)) <= 3
                     OR b.name ~* '^(test|testing|asdf|qwerty|dummy|sample|xxx+|abc|demo)\\y'
                     OR b.name !~* '[a-z\\u0980-\\u09FF]'
                     OR (b.name ~* '^[a-z ]+$' AND b.name !~* '[aeiouy]')
                     OR b.name ~* '(.)\\1{3,}')""",
            Check.INACTIVE, "b.review_count = 0 AND b.updated_at < now() - interval '90 days' AND b.created_at < now() - interval '90 days'");

    private final JdbcTemplate jdbc;
    private final AuditLogService auditLogService;

    public DataCheckService(JdbcTemplate jdbc, AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.auditLogService = auditLogService;
    }

    public Map<Check, Long> counts() {
        Map<Check, Long> out = new LinkedHashMap<>();
        for (Check check : Check.values()) {
            out.put(check, jdbc.queryForObject("SELECT count(*) FROM (" + SELECT + WHERE.get(check) + ") x", Long.class));
        }
        return out;
    }

    public List<Map<String, Object>> rows(Check check, int limit) {
        return jdbc.queryForList(SELECT + WHERE.get(check) + " ORDER BY b.created_at DESC LIMIT ?", limit);
    }

    @Transactional
    public int softDelete(Collection<UUID> ids, String reason) {
        String why = VerificationService.requireReason(reason);
        if (ids == null || ids.isEmpty()) {
            throw new BadRequestException("Select at least one listing.");
        }
        int deleted = 0;
        for (UUID id : ids) {
            List<String> names = jdbc.queryForList("SELECT name FROM business WHERE id = ? AND deleted_at IS NULL", String.class, id);
            if (names.isEmpty()) {
                continue;
            }
            jdbc.update("UPDATE business SET deleted_at = now(), updated_at = now() WHERE id = ?", id);
            auditLogService.record("BUSINESS", id, "BUSINESS_SOFT_DELETED", why,
                    Map.of("name", names.get(0), "deleted", false), Map.of("deleted", true, "via", "data checks"));
            deleted++;
        }
        return deleted;
    }
}
