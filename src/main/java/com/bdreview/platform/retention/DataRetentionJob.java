package com.bdreview.platform.retention;

import com.bdreview.platform.admin.service.AdminAnalyticsService;
import com.bdreview.platform.health.JobRunRecorder;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Daily data retention (V69). Deletes in batches ({@code retention.batch-size} rows, one short
 * transaction each) so no run holds long locks or bloats the WAL:
 * <ul>
 *   <li>notifications: read ones after 90 days, unread ones after 180;</li>
 *   <li>search_log after 180 days — rolled up into search_log_daily in the same statement, so
 *       Analytics keeps the history;</li>
 *   <li>user_login_event after 180 days, scheduled_job_run after 60;</li>
 *   <li>audit_log after 2 years — moderation and financial entries never earlier than that;</li>
 *   <li>soft-deleted community comments, then posts, after 30 days (posts with a paid boost are kept).</li>
 * </ul>
 * Periods come from {@link DataRetentionSettings}. ShedLock keeps it to one instance at a time.
 */
@Component
public class DataRetentionJob {

    public static final String JOB_NAME = "data-retention";

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);
    private static final String TZ = AdminAnalyticsService.TZ;

    private final JdbcTemplate jdbc;
    private final DataRetentionSettings settings;
    private final JobRunRecorder jobRuns;
    private final boolean enabled;
    private final int batchSize;
    private final int maxBatches;

    public DataRetentionJob(JdbcTemplate jdbc, DataRetentionSettings settings, JobRunRecorder jobRuns,
                            @Value("${retention.enabled}") boolean enabled,
                            @Value("${retention.batch-size}") int batchSize,
                            @Value("${retention.max-batches-per-run}") int maxBatches) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.jobRuns = jobRuns;
        this.enabled = enabled;
        this.batchSize = Math.max(100, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
    }

    @Scheduled(cron = "${retention.cron}", zone = TZ)
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        jobRuns.trackWithSummary(JOB_NAME, () -> {
            if (!enabled) {
                return "Skipped: retention is turned off (RETENTION_ENABLED=false)";
            }
            String summary = purge(settings.effective());
            log.info("Data retention: {}", summary);
            return summary;
        });
    }

    String purge(DataRetentionSettings.Effective p) {
        List<String> parts = new ArrayList<>();

        count(parts, "read notifications", () -> jdbc.update("""
                DELETE FROM notification WHERE id IN (
                    SELECT id FROM notification
                    WHERE (status = 'READ' OR read_at IS NOT NULL) AND created_at < now() - make_interval(days => ?)
                    LIMIT ?)
                """, p.notificationsReadDays(), batchSize));
        count(parts, "unread notifications", () -> jdbc.update("""
                DELETE FROM notification WHERE id IN (
                    SELECT id FROM notification
                    WHERE status <> 'READ' AND read_at IS NULL AND created_at < now() - make_interval(days => ?)
                    LIMIT ?)
                """, p.notificationsUnreadDays(), batchSize));

        // Whole Dhaka days only, so each day's summary row is complete. Delete + roll-up are one
        // statement: a batch is either summarised and gone, or untouched.
        count(parts, "search log rows (rolled up)", () -> {
            Long n = jdbc.queryForObject("""
                    WITH batch AS (
                        DELETE FROM search_log WHERE id IN (
                            SELECT id FROM search_log
                            WHERE created_at < (((now() AT TIME ZONE '%1$s')::date - ?)::timestamp AT TIME ZONE '%1$s')
                            ORDER BY id LIMIT ?)
                        RETURNING created_at, normalized, area_id, area_label, result_count
                    ), rolled AS (
                        INSERT INTO search_log_daily (day, normalized, area, searches, zero_result_searches, result_count_sum, last_searched,
                                                      last_zero_result_at)
                        SELECT (b.created_at AT TIME ZONE '%1$s')::date, b.normalized, coalesce(a.name, b.area_label, '(any area)'),
                               count(*), count(*) FILTER (WHERE b.result_count = 0), sum(b.result_count), max(b.created_at),
                               max(b.created_at) FILTER (WHERE b.result_count = 0)
                        FROM batch b LEFT JOIN area a ON a.id = b.area_id
                        GROUP BY 1, 2, 3
                        ON CONFLICT (day, normalized, area) DO UPDATE SET
                            searches = search_log_daily.searches + EXCLUDED.searches,
                            zero_result_searches = search_log_daily.zero_result_searches + EXCLUDED.zero_result_searches,
                            result_count_sum = search_log_daily.result_count_sum + EXCLUDED.result_count_sum,
                            last_searched = greatest(search_log_daily.last_searched, EXCLUDED.last_searched),
                            last_zero_result_at = greatest(search_log_daily.last_zero_result_at, EXCLUDED.last_zero_result_at)
                        RETURNING 1
                    )
                    SELECT count(*) FROM batch
                    """.formatted(TZ), Long.class, p.searchLogDays(), batchSize);
            return n == null ? 0 : n.intValue();
        });

        count(parts, "login events", () -> jdbc.update("""
                DELETE FROM user_login_event WHERE id IN (
                    SELECT id FROM user_login_event WHERE created_at < now() - make_interval(days => ?) LIMIT ?)
                """, p.loginEventsDays(), batchSize));
        count(parts, "job runs", () -> jdbc.update("""
                DELETE FROM scheduled_job_run WHERE id IN (
                    SELECT id FROM scheduled_job_run WHERE started_at < now() - make_interval(days => ?) LIMIT ?)
                """, p.jobRunsDays(), batchSize));

        // Audit: housekeeping types follow the setting; everything else (moderation, financial,
        // security, and any type not listed) waits at least PROTECTED_AUDIT_MIN_DAYS.
        String[] unprotected = DataRetentionSettings.UNPROTECTED_AUDIT_TYPES.toArray(String[]::new);
        count(parts, "audit entries", () -> jdbc.update("""
                DELETE FROM audit_log WHERE id IN (
                    SELECT id FROM audit_log
                    WHERE entity_type = ANY (?) AND created_at < now() - make_interval(days => ?)
                    LIMIT ?)
                """, unprotected, p.auditLogDays(), batchSize)
                + jdbc.update("""
                DELETE FROM audit_log WHERE id IN (
                    SELECT id FROM audit_log
                    WHERE NOT (entity_type = ANY (?)) AND created_at < now() - make_interval(days => ?)
                    LIMIT ?)
                """, unprotected, p.protectedAuditDays(), batchSize));

        // Comments first, leaves only: a deleted comment that still has replies stays as the
        // "[deleted]" placeholder of its thread. Each pass frees the next level up, so this one
        // repeats until a pass deletes nothing rather than stopping at the first short batch.
        count(parts, "deleted comments", true, () -> jdbc.update("""
                DELETE FROM community_post_comment WHERE id IN (
                    SELECT c.id FROM community_post_comment c
                    WHERE c.deleted_at < now() - make_interval(days => ?)
                      AND NOT EXISTS (SELECT 1 FROM community_post_comment r WHERE r.parent_comment_id = c.id)
                    LIMIT ?)
                """, p.softDeletedContentDays(), batchSize));
        // A post takes its comments, reactions, poll, photos and promotion sidecar with it (ON DELETE
        // CASCADE). Posts with a boost are kept: the boost is a payment record.
        count(parts, "deleted posts", () -> jdbc.update("""
                DELETE FROM community_post WHERE id IN (
                    SELECT p.id FROM community_post p
                    WHERE p.deleted_at < now() - make_interval(days => ?)
                      AND NOT EXISTS (SELECT 1 FROM boost b WHERE b.post_id = p.id)
                    LIMIT ?)
                """, p.softDeletedContentDays(), batchSize));

        return parts.isEmpty() ? "Nothing to delete" : String.join(", ", parts);
    }

    private void count(List<String> parts, String label, IntSupplier batch) {
        count(parts, label, false, batch);
    }

    /**
     * Runs one batch at a time until a batch comes back short (or, with {@code untilEmpty}, until
     * one deletes nothing), or the per-run cap is reached.
     */
    private void count(List<String> parts, String label, boolean untilEmpty, IntSupplier batch) {
        long total = 0;
        int batches = 0;
        int n;
        boolean more;
        do {
            n = batch.getAsInt();
            total += n;
            batches++;
            more = untilEmpty ? n > 0 : n >= batchSize;
            if (more && n >= batchSize) {
                pause();
            }
        } while (more && batches < maxBatches);
        if (total > 0) {
            parts.add(total + " " + label + (more ? " (more next run)" : ""));
        }
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
