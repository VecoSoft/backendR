package com.bdreview.platform.search;

import com.bdreview.platform.common.RateLimitExceededException;
import com.bdreview.platform.common.RedisRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-client fixed-window limiter for the two public, unauthenticated search endpoints, keyed by
 * the client IP (the real one: Caddy resolves it from Cloudflare's header and Tomcat's
 * RemoteIpValve applies it). Counted in Redis, so the limit holds across API instances. Limits are
 * far above what a person typing produces — suggestions are debounced client-side.
 */
@Component
public class SearchRateLimiter {

    static final int SEARCH_PER_MINUTE = 120;
    static final int SUGGEST_PER_MINUTE = 300;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RedisRateLimiter limiter;

    public SearchRateLimiter(RedisRateLimiter limiter) {
        this.limiter = limiter;
    }

    public void checkSearch(String clientKey) {
        check("search:" + clientKey, SEARCH_PER_MINUTE);
    }

    public void checkSuggest(String clientKey) {
        check("suggest:" + clientKey, SUGGEST_PER_MINUTE);
    }

    private void check(String key, int limit) {
        if (!limiter.tryAcquire(key, limit, WINDOW)) {
            throw new RateLimitExceededException("Too many searches — please wait a moment and try again.");
        }
    }
}
