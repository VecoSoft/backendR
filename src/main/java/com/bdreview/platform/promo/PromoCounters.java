package com.bdreview.platform.promo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Short-lived counters for promotion serving (V58): the per-viewer daily frequency cap and the
 * 30-minute impression dedupe. Keyed by a session hash — SHA-256 of the client's random
 * per-browser id plus the day — so nothing identifies a person, and the hash can't be linked
 * across days. Kept in Redis only (shared by every instance); while Redis is down serving goes on
 * without caps or dedupe, so it never breaks and no instance keeps its own counts.
 */
@Component
public class PromoCounters {

    private static final Logger log = LoggerFactory.getLogger(PromoCounters.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Duration REDIS_BACKOFF = Duration.ofSeconds(60);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private volatile long redisDisabledUntil;

    public PromoCounters(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** Null for a missing/malformed id — callers then skip per-viewer caps. */
    public static String sessionHash(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            return null;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(("jachai-promo:" + LocalDate.now(ZONE) + ":" + sessionId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** How many times this boost was already served to this session today. */
    public long servedToday(String sessionHash, java.util.UUID boostId) {
        return get("promo:fc:" + LocalDate.now(ZONE) + ":" + boostId + ":" + sessionHash);
    }

    public void recordServed(String sessionHash, java.util.UUID boostId) {
        increment("promo:fc:" + LocalDate.now(ZONE) + ":" + boostId + ":" + sessionHash, Duration.ofHours(26));
    }

    /** True the first time within 30 minutes for this (session, event, target) — the dedupe for impressions. */
    public boolean firstWithin30Minutes(String sessionHash, String event, String target) {
        return increment("promo:dd:" + sessionHash + ":" + event + ":" + target, Duration.ofMinutes(30)) == 1;
    }

    private long get(String key) {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String v = redis.opsForValue().get(key);
                return v == null ? 0 : Long.parseLong(v);
            } catch (Exception e) {
                redisFailed(e);
            }
        }
        return 0; // Redis down: no frequency cap rather than a per-instance one
    }

    private long increment(String key, Duration ttl) {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                Long n = redis.opsForValue().increment(key);
                if (n != null && n == 1) {
                    redis.expire(key, ttl);
                }
                return n == null ? 1 : n;
            } catch (Exception e) {
                redisFailed(e);
            }
        }
        return 1; // Redis down: count it (no dedupe) rather than keep per-instance state
    }

    private StringRedisTemplate redis() {
        return System.currentTimeMillis() < redisDisabledUntil ? null : redisProvider.getIfAvailable();
    }

    private void redisFailed(Exception e) {
        if (System.currentTimeMillis() >= redisDisabledUntil) {
            log.warn("Redis unavailable for promotion counters ({}); serving without frequency caps for {}s",
                    e.getMessage(), REDIS_BACKOFF.toSeconds());
        }
        redisDisabledUntil = System.currentTimeMillis() + REDIS_BACKOFF.toMillis();
    }
}
