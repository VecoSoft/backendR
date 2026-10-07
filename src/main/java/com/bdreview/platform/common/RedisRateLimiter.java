package com.bdreview.platform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Fixed-window counters in Redis, shared by every API instance (so a limit holds no matter which
 * instance a request lands on). INCR and the first EXPIRE run in one Lua script, so a window can't
 * end up without a TTL. If Redis is unreachable the limiter lets requests through (and skips Redis
 * for 30 seconds): a rate limit must never take the site down.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String PREFIX = "jachai:rl:";
    private static final Duration BACKOFF = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return c
            """, Long.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private volatile long disabledUntil;

    public RedisRateLimiter(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** Counts one hit for {@code key}; true while the window's count is within {@code limit}. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        StringRedisTemplate redis = System.currentTimeMillis() < disabledUntil ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            return true;
        }
        try {
            long bucket = System.currentTimeMillis() / window.toMillis();
            Long count = redis.execute(INCR_WITH_TTL, List.of(PREFIX + key + ":" + bucket), String.valueOf(window.toMillis()));
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            disabledUntil = System.currentTimeMillis() + BACKOFF.toMillis();
            log.warn("Redis unavailable for rate limiting ({}); not limiting for {}s", e.getClass().getSimpleName(), BACKOFF.toSeconds());
            return true;
        }
    }
}
