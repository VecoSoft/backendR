package com.bdreview.platform.community.settings;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.community.CommunityTopicRepository;
import com.bdreview.platform.moderation.AuditLogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.*;

/**
 * Reads/writes the {@code community_settings} document and the topic list, cached as one
 * {@link CommunityConfig} snapshot:
 * <ol>
 *   <li>a tiny in-process cache ({@value #LOCAL_TTL_MS} ms) so a busy feed doesn't hit Redis per
 *       request,</li>
 *   <li>Redis ({@code community:config:v1}, {@link #REDIS_TTL} TTL) shared by every instance,</li>
 *   <li>the database.</li>
 * </ol>
 * Every save evicts both caches (after commit), so the next request on this instance sees the
 * change immediately and other instances within the local TTL. If Redis is unreachable the
 * service logs once, skips Redis for {@link #REDIS_BACKOFF} and serves from the database — the
 * community keeps working without Redis.
 */
@Service
public class CommunitySettingsService {

    private static final Logger log = LoggerFactory.getLogger(CommunitySettingsService.class);

    static final String REDIS_KEY = "community:config:v1";
    private static final Duration REDIS_TTL = Duration.ofSeconds(60);
    private static final Duration REDIS_BACKOFF = Duration.ofSeconds(60);
    private static final long LOCAL_TTL_MS = 5_000;

    private final JdbcTemplate jdbcTemplate;
    private final CommunityTopicRepository topicRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final AuditLogService auditLogService;

    private volatile CommunityConfig localSnapshot;
    private volatile long localLoadedAt;
    private volatile long redisDisabledUntil;

    public CommunitySettingsService(JdbcTemplate jdbcTemplate,
                                    CommunityTopicRepository topicRepository,
                                    ObjectMapper objectMapper,
                                    ObjectProvider<StringRedisTemplate> redisProvider,
                                    AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.topicRepository = topicRepository;
        this.objectMapper = objectMapper;
        this.redisProvider = redisProvider;
        this.auditLogService = auditLogService;
    }

    // -----------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------

    public CommunityConfig config() {
        CommunityConfig snapshot = localSnapshot;
        if (snapshot != null && System.currentTimeMillis() - localLoadedAt < LOCAL_TTL_MS) {
            return snapshot;
        }
        snapshot = readRedis();
        if (snapshot == null) {
            snapshot = loadFromDatabase();
            writeRedis(snapshot);
        }
        localSnapshot = snapshot;
        localLoadedAt = System.currentTimeMillis();
        return snapshot;
    }

    public CommunitySettings settings() {
        return config().settings();
    }

    /** Uncached — the admin settings form always edits the stored document. */
    public CommunitySettings loadStoredSettings() {
        return loadFromDatabase().settings();
    }

    private CommunityConfig loadFromDatabase() {
        String json = jdbcTemplate.query("SELECT settings::text FROM community_settings WHERE id = 1",
                rs -> rs.next() ? rs.getString(1) : null);
        CommunitySettings settings;
        try {
            settings = json == null ? new CommunitySettings() : objectMapper.readValue(json, CommunitySettings.class);
        } catch (JsonProcessingException e) {
            log.error("community_settings holds unreadable JSON — falling back to defaults", e);
            settings = new CommunitySettings();
        }
        List<TopicView> topics = topicRepository.findAllByOrderByPositionAscLabelAsc().stream().map(TopicView::from).toList();
        return new CommunityConfig(settings, topics);
    }

    private CommunityConfig readRedis() {
        StringRedisTemplate redis = redisOrNull();
        if (redis == null) {
            return null;
        }
        try {
            String cached = redis.opsForValue().get(REDIS_KEY);
            return cached == null ? null : objectMapper.readValue(cached, CommunityConfig.class);
        } catch (Exception e) {
            redisFailed(e);
            return null;
        }
    }

    private void writeRedis(CommunityConfig snapshot) {
        StringRedisTemplate redis = redisOrNull();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(REDIS_KEY, objectMapper.writeValueAsString(snapshot), REDIS_TTL);
        } catch (Exception e) {
            redisFailed(e);
        }
    }

    /** Drops both cache layers; call after any settings/topic change (runs after commit when inside a transaction). */
    public void evict() {
        Runnable doEvict = () -> {
            localSnapshot = null;
            StringRedisTemplate redis = redisOrNull();
            if (redis != null) {
                try {
                    redis.delete(REDIS_KEY);
                } catch (Exception e) {
                    redisFailed(e);
                }
            }
        };
        doEvict.run();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvict.run();
                }
            });
        }
    }

    private StringRedisTemplate redisOrNull() {
        if (System.currentTimeMillis() < redisDisabledUntil) {
            return null;
        }
        return redisProvider.getIfAvailable();
    }

    private void redisFailed(Exception e) {
        if (System.currentTimeMillis() >= redisDisabledUntil) {
            log.warn("Redis unavailable for community settings cache ({}); using the database for {}s",
                    e.getMessage(), REDIS_BACKOFF.toSeconds());
        }
        redisDisabledUntil = System.currentTimeMillis() + REDIS_BACKOFF.toMillis();
    }

    // -----------------------------------------------------------------
    // Write (ADMIN only — enforced by the calling controllers + here)
    // -----------------------------------------------------------------

    @Transactional
    public CommunitySettings save(CommunitySettings updated, String reason) {
        CurrentUser.requireRole("ADMIN");
        validate(updated);
        CommunitySettings before = loadStoredSettings();
        String afterJson;
        try {
            afterJson = objectMapper.writeValueAsString(updated);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Settings could not be serialised");
        }
        jdbcTemplate.update("""
                UPDATE community_settings SET settings = CAST(? AS jsonb), updated_by = ?, updated_at = now() WHERE id = 1
                """, afterJson, CurrentUser.idOrNull());
        Map<String, Object[]> diff = diff(before, updated);
        auditLogService.record("COMMUNITY_SETTINGS", AuditLogService.SYSTEM_ACTOR, "SETTINGS_UPDATED",
                reason == null || reason.isBlank() ? "Settings updated (" + diff.size() + " change(s))" : reason,
                diffSide(diff, 0), diffSide(diff, 1));
        evict();
        return updated;
    }

    /** Field-level before/after diff (dotted paths) — what the audit row stores. */
    public Map<String, Object[]> diff(CommunitySettings before, CommunitySettings after) {
        Map<String, Object[]> out = new TreeMap<>();
        flattenDiff("", objectMapper.valueToTree(before), objectMapper.valueToTree(after), out);
        return out;
    }

    private void flattenDiff(String prefix, JsonNode a, JsonNode b, Map<String, Object[]> out) {
        if (a != null && b != null && a.isObject() && b.isObject()) {
            Set<String> keys = new TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                flattenDiff(prefix.isEmpty() ? k : prefix + "." + k, a.get(k), b.get(k), out);
            }
            return;
        }
        if (!Objects.equals(a, b)) {
            out.put(prefix, new Object[]{a, b});
        }
    }

    private static Map<String, Object> diffSide(Map<String, Object[]> diff, int side) {
        Map<String, Object> m = new LinkedHashMap<>();
        diff.forEach((k, v) -> m.put(k, v[side]));
        return m;
    }

    private void validate(CommunitySettings s) {
        var c = s.getContent();
        requireRange("Post body min", c.getPostBodyMin(), 0, 20000);
        requireRange("Post body max", c.getPostBodyMax(), 1, 20000);
        requireRange("Question title min", c.getQuestionTitleMin(), 0, 150);
        requireRange("Question title max", c.getQuestionTitleMax(), 1, 150);
        requireRange("Comment min", c.getCommentMin(), 0, 10000);
        requireRange("Comment max", c.getCommentMax(), 1, 10000);
        requireRange("Max links per post", c.getMaxLinksPerPost(), 0, 100);
        requireRange("Block links for accounts newer than", c.getBlockLinksForAccountsNewerThanDays(), 0, 3650);
        if (c.getPostBodyMin() > c.getPostBodyMax()) {
            throw new BadRequestException("Post body min must not exceed max");
        }
        if (c.getQuestionTitleMin() > c.getQuestionTitleMax()) {
            throw new BadRequestException("Question title min must not exceed max");
        }
        if (c.getCommentMin() > c.getCommentMax()) {
            throw new BadRequestException("Comment min must not exceed max");
        }
        if (!"BLOCK".equals(c.getBannedWordsMode()) && !"FLAG".equals(c.getBannedWordsMode())) {
            throw new BadRequestException("Banned words mode must be BLOCK or FLAG");
        }
        c.setBannedWords(c.getBannedWords() == null ? new ArrayList<>() : new ArrayList<>(c.getBannedWords().stream()
                .filter(Objects::nonNull).map(String::trim).filter(w -> !w.isEmpty()).distinct().toList()));
        var p = s.getPostTypes();
        requireRange("Max images per post", p.getMaxImagesPerPost(), 0, 20);
        requireRange("Max image size (MB)", p.getMaxImageSizeMb(), 1, 25);
        if (!p.isDiscussionEnabled() && !p.isQuestionEnabled() && !p.isRecommendationEnabled() && !p.isPollEnabled()) {
            throw new BadRequestException("At least one post type must stay enabled");
        }
        var r = s.getRateLimits();
        requireRange("Trusted after days", r.getTrustedAfterDays(), 0, 3650);
        for (var tier : List.of(r.getNewUsers(), r.getTrustedUsers())) {
            requireRange("Posts per day", tier.getPostsPerDay(), 0, 10000);
            requireRange("Comments per hour", tier.getCommentsPerHour(), 0, 10000);
            requireRange("Votes per minute", tier.getVotesPerMinute(), 0, 10000);
            requireRange("Reports per day", tier.getReportsPerDay(), 0, 10000);
        }
        requireRange("Approval post count", s.getNewUsers().getApprovalPostCount(), 0, 1000);
        requireRange("Auto-hide report threshold", s.getAutoModeration().getAutoHideReportThreshold(), 0, 1000);
        requireRange("Heavily downvoted threshold", s.getAutoModeration().getHeavilyDownvotedThreshold(), 1, 100000);
        if (s.getRulesMarkdown() != null && s.getRulesMarkdown().length() > 10000) {
            throw new BadRequestException("Community rules text is too long (max 10000 characters)");
        }
        if (s.getAreas().getAllowedAreaIds() == null) {
            s.getAreas().setAllowedAreaIds(new ArrayList<>());
        }
        if (s.getReasonTemplates() == null) {
            s.setReasonTemplates(new ArrayList<>());
        }
        if (s.getPromotions() == null) {
            s.setPromotions(new CommunitySettings.Promotions());
        }
        var promo = s.getPromotions();
        requireRange("Business posts per week", promo.getBusinessPostsPerWeek(), 0, 100);
        requireRange("Minimum business post length", promo.getMinBodyLength(), 0, 2000);
        requireRange("Sponsored feed ratio", promo.getSponsoredFeedRatio(), 0, 100);
        if (promo.getSponsoredFeedRatio() > 0 && promo.getSponsoredFeedRatio() < 3) {
            throw new BadRequestException("Sponsored feed ratio must be 0 (off) or at least 3");
        }
        requireRange("Frequency cap per day", promo.getFrequencyCapPerDay(), 1, 20);
        requireRange("Captions per business per day", promo.getCaptionsPerBusinessPerDay(), 0, 500);
        promo.setBannedCategories(promo.getBannedCategories() == null ? new ArrayList<>() : new ArrayList<>(
                promo.getBannedCategories().stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList()));
        promo.setExtraBannedKeywords(promo.getExtraBannedKeywords() == null ? new ArrayList<>() : new ArrayList<>(
                promo.getExtraBannedKeywords().stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList()));
        for (String number : List.of(nullToEmpty(promo.getBkashNumber()), nullToEmpty(promo.getNagadNumber()))) {
            if (!number.isEmpty() && !number.matches("^\\+?[0-9 \\-]{6,20}$")) {
                throw new BadRequestException("Merchant numbers may only contain digits, spaces, dashes and a leading +");
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static void requireRange(String label, int value, int min, int max) {
        if (value < min || value > max) {
            throw new BadRequestException(label + " must be between " + min + " and " + max);
        }
    }
}
