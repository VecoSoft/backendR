package com.bdreview.platform.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * Logs every public search that has a query (first page only) with its result count (V67
 * {@code search_log}) for System → Analytics: top queries and zero-result queries by area — the
 * listings worth adding. Async and best-effort; never slows down or fails a search.
 */
@Service
public class SearchLogService {

    private static final Logger log = LoggerFactory.getLogger(SearchLogService.class);

    private final JdbcTemplate jdbc;

    public SearchLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void log(String query, UUID areaId, String areaLabel, UUID categoryId, long resultCount, String source, UUID userId) {
        if (query == null || query.isBlank()) {
            return;
        }
        String q = query.trim();
        if (q.length() > 200) {
            q = q.substring(0, 200);
        }
        try {
            jdbc.update("""
                    INSERT INTO search_log (query, normalized, area_id, area_label, category_id, result_count, source, user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, q, normalize(q), areaId, areaLabel, categoryId, (int) Math.min(resultCount, Integer.MAX_VALUE), source, userId);
        } catch (RuntimeException e) {
            log.debug("Search log write failed: {}", e.getMessage());
        }
    }

    static String normalize(String q) {
        return q.toLowerCase(Locale.ROOT).replaceAll("\s+", " ").trim();
    }
}
