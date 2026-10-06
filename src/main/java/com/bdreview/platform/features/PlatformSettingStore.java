package com.bdreview.platform.features;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code platform_setting} table (V63): admin overrides of deployment defaults, one row per
 * overridden key. Read on almost every API request (feature gates), so the whole table is held
 * in a {@value #TTL_MS} ms in-process snapshot; a write on this instance evicts it at once.
 */
@Component
public class PlatformSettingStore {

    private static final long TTL_MS = 5_000;

    public record Row(String key, boolean enabled, String message, UUID updatedBy, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    private volatile Map<String, Row> snapshot;
    private volatile long loadedAt;

    public PlatformSettingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> get(String key) {
        return Optional.ofNullable(rows().get(key));
    }

    /** Insert/replace the override for {@code key}. */
    public void put(String key, boolean enabled, String message, UUID updatedBy) {
        jdbc.update("""
                INSERT INTO platform_setting (setting_key, enabled, message, updated_by, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (setting_key) DO UPDATE
                    SET enabled = EXCLUDED.enabled, message = EXCLUDED.message,
                        updated_by = EXCLUDED.updated_by, updated_at = now()
                """, key, enabled, message, updatedBy);
        evict();
    }

    /** Drop the override so the deployment default applies again. */
    public void clear(String key) {
        jdbc.update("DELETE FROM platform_setting WHERE setting_key = ?", key);
        evict();
    }

    public void evict() {
        snapshot = null;
    }

    private Map<String, Row> rows() {
        Map<String, Row> current = snapshot;
        if (current != null && System.currentTimeMillis() - loadedAt < TTL_MS) {
            return current;
        }
        Map<String, Row> loaded = new HashMap<>();
        jdbc.query("SELECT setting_key, enabled, message, updated_by, updated_at FROM platform_setting", rs -> {
            String key = rs.getString("setting_key");
            loaded.put(key, new Row(key, rs.getBoolean("enabled"), rs.getString("message"),
                    rs.getObject("updated_by", UUID.class),
                    rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()));
        });
        snapshot = loaded;
        loadedAt = System.currentTimeMillis();
        return loaded;
    }
}
