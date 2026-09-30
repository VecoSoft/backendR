package com.bdreview.platform.search;

import com.bdreview.platform.common.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-client fixed-window limiter for the two public, unauthenticated search endpoints. In-memory
 * (per instance) on purpose: it only has to stop one client hammering the ranked query, not enforce
 * an exact global quota. Limits are far above what a person typing produces — suggestions are
 * debounced client-side, so even fast typing stays well under a few requests per second.
 */
@Component
public class SearchRateLimiter {

    static final int SEARCH_PER_MINUTE = 120;
    static final int SUGGEST_PER_MINUTE = 300;
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 20_000;

    private record Window(long startedAt, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void checkSearch(String clientKey) {
        check("s:" + clientKey, SEARCH_PER_MINUTE);
    }

    public void checkSuggest(String clientKey) {
        check("g:" + clientKey, SUGGEST_PER_MINUTE);
    }

    private void check(String key, int limit) {
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(e -> now - e.getValue().startedAt() > WINDOW_MS);
        }
        Window w = windows.compute(key, (k, existing) ->
                existing == null || now - existing.startedAt() > WINDOW_MS
                        ? new Window(now, new AtomicInteger())
                        : existing);
        if (w.count().incrementAndGet() > limit) {
            throw new RateLimitExceededException("Too many searches — please wait a moment and try again.");
        }
    }
}
