package com.bdreview.platform.health;

import com.bdreview.platform.commerce.BookingService;
import com.bdreview.platform.commerce.OrderService;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.community.moderation.CommunityRestrictionExpiryJob;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.notification.BroadcastService;
import com.bdreview.platform.otp.LoggingSmsGatewayService;
import com.bdreview.platform.otp.SmsGatewayService;
import com.bdreview.platform.promo.PromoExpiryJob;
import com.bdreview.platform.retention.DataRetentionJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * System → Health (V67): live checks of the database, Redis, the ML service, the SMS gateway and
 * file storage (with disk usage), plus the scheduled jobs with their last runs and a "Run now"
 * for the ones that are safe to trigger by hand. No external monitoring service involved.
 */
@Service
public class HealthService {

    public record Check(String name, String status, Long latencyMs, String detail, Instant checkedAt) {
        public boolean up() {
            return "UP".equals(status);
        }
    }

    /** A scheduled job as listed on the page; {@code invoker} calls the bean method through its proxy. */
    public record Job(String name, String label, String schedule, Duration rate, boolean safe, Runnable invoker) {
    }

    public record JobView(Job job, Map<String, Object> lastRun, Instant nextRun, long failuresLast24h) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final WebClient mlClient;
    private final SmsGatewayService sms;
    private final Path storageRoot;
    private final JobRunRecorder jobRuns;
    private final AuditLogService auditLogService;
    private final List<Job> jobs;

    public HealthService(JdbcTemplate jdbc, ObjectProvider<StringRedisTemplate> redis, WebClient mlServiceWebClient,
                         SmsGatewayService sms, @Value("${app.storage.local-dir}") String storageDir,
                         JobRunRecorder jobRuns, AuditLogService auditLogService,
                         ObjectProvider<OrderService> orders, ObjectProvider<BookingService> bookings,
                         ObjectProvider<CommunityRestrictionExpiryJob> restrictions, ObjectProvider<PromoExpiryJob> promo,
                         ObjectProvider<BroadcastService> broadcasts, ObjectProvider<DataRetentionJob> retention,
                         @Value("${retention.cron}") String retentionCron) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mlClient = mlServiceWebClient;
        this.sms = sms;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.jobRuns = jobRuns;
        this.auditLogService = auditLogService;
        this.jobs = List.of(
                new Job("order-auto-cancel", "Auto-cancel unanswered orders", "every 5 min", Duration.ofMinutes(5), true,
                        () -> orders.getObject().autoExpireStalePendingOrders()),
                new Job("booking-auto-close", "Close past bookings (completed / no-show)", "every 15 min", Duration.ofMinutes(15), true,
                        () -> bookings.getObject().autoExpirePastBookings()),
                new Job("community-restriction-expiry", "Expire community restrictions", "every 1 min", Duration.ofMinutes(1), true,
                        () -> restrictions.getObject().expireRestrictions()),
                new Job("promo-expiry", "Expire business posts and boosts", "every 2 min", Duration.ofMinutes(2), true,
                        () -> promo.getObject().run()),
                new Job("broadcast-dispatch", "Send scheduled broadcasts", "every 1 min", Duration.ofMinutes(1), true,
                        () -> broadcasts.getObject().dispatchDue()),
                new Job(DataRetentionJob.JOB_NAME, "Data retention (delete old logs and deleted content)",
                        "cron " + retentionCron + " (Dhaka)", Duration.ofDays(1), true,
                        () -> retention.getObject().run()));
    }

    // ---------------------------------------------------------------- status cards

    public List<Check> checks() {
        return List.of(database(), redis(), mlService(), smsGateway(), storage());
    }

    private Check database() {
        long t0 = System.nanoTime();
        try {
            String version = jdbc.queryForObject("SELECT split_part(version(), ' ', 2)", String.class);
            Long size = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
            return new Check("Database", "UP", ms(t0), "PostgreSQL " + version + " · " + human(size == null ? 0 : size), Instant.now());
        } catch (RuntimeException e) {
            return new Check("Database", "DOWN", ms(t0), e.getMessage(), Instant.now());
        }
    }

    private Check redis() {
        long t0 = System.nanoTime();
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return new Check("Redis", "NOT_CONFIGURED", null, "No Redis client configured", Instant.now());
        }
        try {
            String pong = template.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            return new Check("Redis", "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN", ms(t0), "PING → " + pong
                    + " (community settings fall back to the database when Redis is down)", Instant.now());
        } catch (RuntimeException e) {
            return new Check("Redis", "DOWN", ms(t0), rootMessage(e) + " — the app falls back to the database", Instant.now());
        }
    }

    private Check mlService() {
        long t0 = System.nanoTime();
        try {
            var response = mlClient.get().uri("/health").retrieve().toBodilessEntity().block(Duration.ofSeconds(3));
            int code = response == null ? 0 : response.getStatusCode().value();
            return new Check("ML service", code >= 200 && code < 300 ? "UP" : "DOWN", ms(t0), "GET /health → " + code, Instant.now());
        } catch (RuntimeException e) {
            return new Check("ML service", "DOWN", ms(t0), rootMessage(e) + " — reviews keep their default status until it's back", Instant.now());
        }
    }

    private Check smsGateway() {
        if (sms instanceof LoggingSmsGatewayService) {
            return new Check("SMS gateway", "NOT_CONFIGURED", null,
                    "Development gateway — OTPs and SMS are written to the server log, not sent", Instant.now());
        }
        return new Check("SMS gateway", "UP", null, sms.getClass().getSimpleName(), Instant.now());
    }

    private Check storage() {
        long t0 = System.nanoTime();
        try {
            Files.createDirectories(storageRoot);
            FileStore store = Files.getFileStore(storageRoot);
            long used = directorySize(storageRoot);
            Path probe = Files.createTempFile(storageRoot, ".health", ".tmp");
            Files.delete(probe);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            String status = free < total / 20 ? "WARN" : "UP";
            return new Check("File storage", status, ms(t0),
                    "Local disk " + storageRoot + " · uploads " + human(used) + " · disk " + human(total - free) + " used of "
                            + human(total) + " (" + human(free) + " free)", Instant.now());
        } catch (IOException | RuntimeException e) {
            return new Check("File storage", "DOWN", ms(t0), rootMessage(e), Instant.now());
        }
    }

    // ---------------------------------------------------------------- table sizes

    /** A table's on-disk size: {@code total} includes its indexes and TOAST data. */
    public record TableSize(String name, String total, String table, String indexes, long estimatedRows) {
    }

    /** The 10 largest tables by pg_total_relation_size. Row counts are planner estimates (no full scans). */
    public List<TableSize> largestTables() {
        return jdbc.query("""
                SELECT c.relname AS name, pg_total_relation_size(c.oid) AS total, pg_relation_size(c.oid) AS heap,
                       pg_indexes_size(c.oid) AS indexes, greatest(c.reltuples, 0)::bigint AS est_rows
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'p') AND n.nspname = current_schema()
                ORDER BY pg_total_relation_size(c.oid) DESC
                LIMIT 10
                """, (rs, i) -> new TableSize(rs.getString("name"), human(rs.getLong("total")), human(rs.getLong("heap")),
                human(rs.getLong("indexes")), rs.getLong("est_rows")));
    }

    // ---------------------------------------------------------------- jobs

    public List<JobView> jobs() {
        List<JobView> out = new ArrayList<>();
        for (Job job : jobs) {
            List<Map<String, Object>> last = jdbc.queryForList(
                    "SELECT * FROM scheduled_job_run WHERE job_name = ? ORDER BY started_at DESC LIMIT 1", job.name());
            Map<String, Object> lastRun = last.isEmpty() ? null : last.get(0);
            Instant next = lastRun == null ? null
                    : ((java.sql.Timestamp) lastRun.get("started_at")).toInstant().plus(job.rate());
            Long failures = jdbc.queryForObject("""
                    SELECT count(*) FROM scheduled_job_run WHERE job_name = ? AND result = 'FAILED' AND started_at > now() - interval '24 hours'
                    """, Long.class, job.name());
            out.add(new JobView(job, lastRun, next, failures == null ? 0 : failures));
        }
        return out;
    }

    public List<Map<String, Object>> runs(String jobName, int limit) {
        return jdbc.queryForList("SELECT * FROM scheduled_job_run WHERE job_name = ? ORDER BY started_at DESC LIMIT ?", jobName, limit);
    }

    /** "Run now" — only for jobs marked safe; audited with the admin's reason. */
    public void runNow(String jobName, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        Job job = jobs.stream().filter(j -> j.name().equals(jobName)).findFirst()
                .orElseThrow(() -> new BadRequestException("Unknown job."));
        if (!job.safe()) {
            throw new BadRequestException("This job can't be run by hand.");
        }
        auditLogService.record("SCHEDULED_JOB", null, "JOB_RUN_NOW", reason.trim(), null, Map.of("job", jobName));
        jobRuns.runManually(job.invoker());
    }

    // ----------------------------------------------------------------

    private static long directorySize(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).limit(200_000).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    private static Long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[i]);
    }
}
