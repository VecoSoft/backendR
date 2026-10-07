package com.bdreview.platform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Small read-through cache in Redis, shared by every API instance: an eviction on one instance
 * (e.g. an admin saving settings) is seen by all of them at once. Nothing is cached in the JVM.
 * If Redis is unreachable, reads go straight to the loader (the database) — slower, never wrong —
 * and Redis is skipped for 30 seconds so a dead Redis doesn't add a timeout to every request.
 */
@Component
public class SharedCache {

    private static final Logger log = LoggerFactory.getLogger(SharedCache.class);
    private static final String PREFIX = "jachai:cache:";
    private static final Duration BACKOFF = Duration.ofSeconds(30);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private volatile long disabledUntil;

    public SharedCache(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** The cached value, or {@code loader}'s result (cached for {@code ttl}). A null result is not cached. */
    public String get(String key, Duration ttl, Supplier<String> loader) {
        Optional<String> hit = peek(key);
        if (hit.isPresent()) {
            return hit.get();
        }
        String value = loader.get();
        if (value != null) {
            put(key, value, ttl);
        }
        return value;
    }

    public Optional<String> peek(String key) {
        StringRedisTemplate redis = redis();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redis.opsForValue().get(PREFIX + key));
        } catch (RuntimeException e) {
            failed(e);
            return Optional.empty();
        }
    }

    public void put(String key, String value, Duration ttl) {
        StringRedisTemplate redis = redis();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + key, value, ttl);
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    public void evict(String... keys) {
        StringRedisTemplate redis = redis();
        if (redis == null || keys.length == 0) {
            return;
        }
        try {
            redis.delete(List.of(keys).stream().map(k -> PREFIX + k).toList());
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /**
     * Evicts now and again once the surrounding transaction commits, so another instance can't
     * re-cache the old row in between. Outside a transaction it just evicts.
     */
    public void evictAfterCommit(String... keys) {
        evict(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(keys);
                }
            });
        }
    }

    private StringRedisTemplate redis() {
        return System.currentTimeMillis() < disabledUntil ? null : redisProvider.getIfAvailable();
    }

    private void failed(RuntimeException e) {
        disabledUntil = System.currentTimeMillis() + BACKOFF.toMillis();
        log.warn("Redis unavailable for the shared cache ({}); reading from the database for {}s",
                e.getClass().getSimpleName(), BACKOFF.toSeconds());
    }
}
