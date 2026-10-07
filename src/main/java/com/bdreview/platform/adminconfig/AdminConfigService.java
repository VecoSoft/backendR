package com.bdreview.platform.adminconfig;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.SharedCache;
import com.bdreview.platform.moderation.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Admin-editable configuration documents (V65 {@code admin_config}): Commerce settings, Review
 * policy and Homepage curation. Each is read on hot paths (order sweep, review submit, homepage
 * search), so it is cached in Redis (shared by every instance) for a minute and evicted on save. Saving needs a
 * reason and writes an audit entry with the before/after documents.
 */
@Service
public class AdminConfigService {

    public static final String COMMERCE = "COMMERCE";
    public static final String REVIEW_POLICY = "REVIEW_POLICY";
    public static final String HOMEPAGE = "HOMEPAGE";
    public static final String RETENTION = "RETENTION";

    private static final Duration TTL = Duration.ofSeconds(60);
    /** Cached marker for "no row saved yet" (defaults apply). */
    private static final String NONE = "-";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final SharedCache cache;

    public AdminConfigService(JdbcTemplate jdbc, ObjectMapper objectMapper, AuditLogService auditLogService,
                              SharedCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    public CommerceConfig commerce() {
        return load(COMMERCE, CommerceConfig.class);
    }

    public ReviewPolicyConfig reviewPolicy() {
        return load(REVIEW_POLICY, ReviewPolicyConfig.class);
    }

    public HomepageConfig homepage() {
        return load(HOMEPAGE, HomepageConfig.class);
    }

    public RetentionConfig retention() {
        return load(RETENTION, RetentionConfig.class);
    }

    /** Range checks live next to the defaults and the protected floor, in DataRetentionSettings. */
    @Transactional
    public void saveRetention(RetentionConfig config, String reason) {
        save(RETENTION, retention(), config, reason);
    }

    @Transactional
    public void saveCommerce(CommerceConfig config, String reason) {
        if (config.getOrderAutoCancelMinutes() < 5 || config.getOrderAutoCancelMinutes() > 7 * 24 * 60) {
            throw new BadRequestException("Order auto-cancel must be between 5 minutes and 7 days.");
        }
        if (config.getBookingNoShowGraceMinutes() < 0 || config.getBookingNoShowGraceMinutes() > 24 * 60) {
            throw new BadRequestException("No-show grace must be between 0 and 1440 minutes.");
        }
        if (config.getMaxActiveOffersPerBusiness() < 1 || config.getMaxActiveOffersPerBusiness() > 100) {
            throw new BadRequestException("Max active offers must be between 1 and 100.");
        }
        save(COMMERCE, commerce(), config, reason);
    }

    @Transactional
    public void saveReviewPolicy(ReviewPolicyConfig config, String reason) {
        if (config.getMinLength() < 1 || config.getMinLength() > 1000) {
            throw new BadRequestException("Minimum length must be between 1 and 1000 characters.");
        }
        if (config.getEditWindowHours() < 0 || config.getEditWindowHours() > 24 * 365) {
            throw new BadRequestException("Edit window must be between 0 and 8760 hours.");
        }
        if (config.getMaxReviewsPerUserPerDay() < 1 || config.getMaxReviewsPerUserPerDay() > 1000) {
            throw new BadRequestException("Max reviews per day must be between 1 and 1000.");
        }
        if (config.getNotRecommendedThreshold() < 1 || config.getHiddenThreshold() > 101
                || config.getNotRecommendedThreshold() > config.getHiddenThreshold()) {
            throw new BadRequestException("Thresholds must satisfy 1 ≤ not-recommended ≤ hidden ≤ 101 (101 = never).");
        }
        save(REVIEW_POLICY, reviewPolicy(), config, reason);
    }

    @Transactional
    public void saveHomepage(HomepageConfig config, String reason) {
        for (HomepageConfig.Section s : List.of(config.getTrending(), config.getMostLoved())) {
            if (s.getMinReviewCount() < 0) {
                throw new BadRequestException("Minimum review count can't be negative.");
            }
            if (s.getPins().size() > HomepageConfig.MAX_PINS) {
                throw new BadRequestException("At most " + HomepageConfig.MAX_PINS + " pins per section.");
            }
            for (HomepageConfig.Pin pin : s.getPins()) {
                if (pin.getBusinessId() == null || pin.getEndsAt() == null) {
                    throw new BadRequestException("Every pin needs a business and an end date.");
                }
            }
        }
        if (config.getHeroTitle() != null && config.getHeroTitle().length() > 120) {
            throw new BadRequestException("Hero title can be at most 120 characters.");
        }
        if (config.getHeroSubtitle() != null && config.getHeroSubtitle().length() > 300) {
            throw new BadRequestException("Hero subtitle can be at most 300 characters.");
        }
        save(HOMEPAGE, homepage(), config, reason);
    }

    /** System write (no reason/audit) — e.g. a moderator approving the pending hero image. */
    @Transactional
    public void writeHomepageSystem(HomepageConfig config) {
        write(HOMEPAGE, config);
    }

    public void evict() {
        cache.evict(cacheKey(COMMERCE), cacheKey(REVIEW_POLICY), cacheKey(HOMEPAGE), cacheKey(RETENTION));
    }

    // -----------------------------------------------------------------

    private void save(String section, Object before, Object after, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        write(section, after);
        auditLogService.record("ADMIN_CONFIG", null, "CONFIG_" + section + "_SAVED", reason.trim(), before, after);
    }

    private void write(String section, Object value) {
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                INSERT INTO admin_config (section, settings, updated_by, updated_at) VALUES (?, ?::jsonb, ?, now())
                ON CONFLICT (section) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, section, json, CurrentUser.idOrNull());
        cache.evictAfterCommit(cacheKey(section));
    }

    private <T> T load(String section, Class<T> type) {
        String json = cache.get(cacheKey(section), TTL, () -> {
            List<String> rows = jdbc.queryForList("SELECT settings::text FROM admin_config WHERE section = ?", String.class, section);
            return rows.isEmpty() ? NONE : rows.get(0);
        });
        try {
            // Parsed per call, so callers always get their own instance to modify.
            return NONE.equals(json) ? type.getDeclaredConstructor().newInstance() : objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable admin_config " + section, e);
        }
    }

    private static String cacheKey(String section) {
        return "admin_config:" + section;
    }
}
