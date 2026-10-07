package com.bdreview.platform.gallery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * One-off, idempotent media migration (deploy/migrate-media.sh runs it in a throwaway container):
 *
 * <ol>
 *   <li>{@code STORAGE_MIGRATE_SOURCE}: copies every file under that folder (the old local
 *       {@code uploads/}) into the configured store, keyed by its relative path. Files already
 *       there with the same size are skipped, so it can be re-run after an interruption.</li>
 *   <li>Generates any missing WebP variants for the copied images.</li>
 *   <li>{@code STORAGE_MIGRATE_REWRITE_FROM}: rewrites stored file URLs that start with an old
 *       API base (comma-separated list, e.g. {@code http://localhost:8085,http://localhost:8095}) to {@code STORAGE_BASE_URL}, in every text,
 *       varchar, text[] and jsonb column. Already-rewritten values don't match, so re-runs are no-ops.</li>
 * </ol>
 * Then the process exits. Does nothing (and doesn't exit) when neither variable is set.
 */
@Component
public class StorageMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageMigrationRunner.class);
    private static final String STORAGE_PATH = "/api/v1/storage/";

    private final ObjectStorageClient storage;
    private final ImageVariantService variants;
    private final JdbcTemplate jdbc;
    private final ApplicationContext context;
    private final String source;
    private final List<String> rewriteFrom;
    private final String baseUrl;

    public StorageMigrationRunner(ObjectStorageClient storage, ImageVariantService variants, JdbcTemplate jdbc,
                                  ApplicationContext context,
                                  @Value("${app.storage.migrate.source:}") String source,
                                  @Value("${app.storage.migrate.rewrite-urls-from:}") String rewriteFrom,
                                  @Value("${app.storage.base-url}") String baseUrl) {
        this.storage = storage;
        this.variants = variants;
        this.jdbc = jdbc;
        this.context = context;
        this.source = source;
        this.rewriteFrom = java.util.Arrays.stream(rewriteFrom.split(","))
                .map(s -> s.trim().replaceAll("/+$", "")).filter(s -> !s.isEmpty()).toList();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (source.isBlank() && rewriteFrom.isEmpty()) {
            return;
        }
        int exit = 0;
        try {
            if (!source.isBlank()) {
                copyFiles(Path.of(source));
            }
            for (String from : rewriteFrom) {
                rewriteUrls(from);
            }
            log.info("Media migration finished.");
        } catch (Exception e) {
            log.error("Media migration failed: {}", e.toString(), e);
            exit = 1;
        }
        int code = exit;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private void copyFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("STORAGE_MIGRATE_SOURCE is not a directory: " + root);
        }
        int copied = 0, skipped = 0, failed = 0, variantsWritten = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        log.info("Media migration: {} files under {}", files.size(), root);
        for (Path file : files) {
            String key = root.relativize(file).toString().replace('\\', '/');
            if (key.startsWith(".") || key.contains("/.")) {
                continue; // .health probes, OS metadata
            }
            try {
                long localSize = Files.size(file);
                OptionalLong remote = storage.size(key);
                byte[] bytes = null;
                if (remote.isPresent() && remote.getAsLong() == localSize) {
                    skipped++;
                } else {
                    bytes = Files.readAllBytes(file);
                    storage.putObject(key, bytes);
                    copied++;
                }
                if (variants.wantsVariants(key)) {
                    if (bytes == null) {
                        bytes = Files.readAllBytes(file);
                    }
                    variantsWritten += variants.generateNow(key, bytes, true);
                }
            } catch (Exception e) {
                failed++;
                log.warn("  could not migrate {}: {}", key, e.toString());
            }
            int done = copied + skipped + failed;
            if (done % 100 == 0) {
                log.info("  ... {} / {}", done, files.size());
            }
        }
        log.info("Media migration: {} copied, {} already there, {} failed, {} variants written", copied, skipped, failed, variantsWritten);
        if (failed > 0) {
            throw new IOException(failed + " file(s) failed; re-run to retry them");
        }
    }

    private void rewriteUrls(String oldBase) {
        String from = oldBase + STORAGE_PATH;
        String to = baseUrl + STORAGE_PATH;
        if (from.equals(to)) {
            log.info("URL rewrite: old and new base are the same, nothing to do");
            return;
        }
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT table_name, column_name, data_type, udt_name FROM information_schema.columns
                WHERE table_schema = current_schema()
                  -- audit_log keeps its records exactly as written
                  AND table_name NOT IN ('flyway_schema_history', 'shedlock', 'spatial_ref_sys', 'audit_log')
                  AND (data_type IN ('text', 'character varying', 'jsonb') OR (data_type = 'ARRAY' AND udt_name IN ('_text', '_varchar')))
                  AND table_name IN (SELECT table_name FROM information_schema.tables
                                     WHERE table_schema = current_schema() AND table_type = 'BASE TABLE')
                """);
        long total = 0;
        for (Map<String, Object> c : columns) {
            String table = quote((String) c.get("table_name"));
            String column = quote((String) c.get("column_name"));
            String type = (String) c.get("data_type");
            String cast = switch (type) {
                case "jsonb" -> "::jsonb";
                case "ARRAY" -> "::" + ("_text".equals(c.get("udt_name")) ? "text[]" : "varchar[]");
                default -> "";
            };
            String asText = type.equals("text") || type.equals("character varying") ? column : column + "::text";
            int n = jdbc.update("UPDATE " + table + " SET " + column + " = replace(" + asText + ", ?, ?)" + cast
                    + " WHERE " + asText + " LIKE ?", from, to, "%" + escapeLike(from) + "%");
            if (n > 0) {
                log.info("  {}.{}: {} row(s)", c.get("table_name"), c.get("column_name"), n);
                total += n;
            }
        }
        log.info("URL rewrite {} -> {}: {} row(s) updated", from, to, total);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
