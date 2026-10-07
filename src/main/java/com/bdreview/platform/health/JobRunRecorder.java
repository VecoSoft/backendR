package com.bdreview.platform.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Records every run of a scheduled job — start, duration, OK/FAILED and the error message — in
 * {@code scheduled_job_run} (V67) for System → Health. Written in its own transaction so a failed
 * job (whose transaction rolls back) is still recorded; the failure is re-thrown afterwards.
 */
@Component
public class JobRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(JobRunRecorder.class);
    private static final ThreadLocal<Boolean> MANUAL = ThreadLocal.withInitial(() -> false);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate independentTx;

    public JobRunRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.independentTx = new TransactionTemplate(transactionManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Wraps a job body. Usage inside a {@code @Scheduled} method: {@code jobs.track("order-auto-cancel", () -> {...})}. */
    public void track(String jobName, Runnable body) {
        trackWithSummary(jobName, () -> {
            body.run();
            return null;
        });
    }

    /** Like {@link #track}, keeping the body's one-line summary (e.g. rows deleted) as the run's message. */
    public void trackWithSummary(String jobName, java.util.function.Supplier<String> body) {
        Instant start = Instant.now();
        long t0 = System.nanoTime();
        try {
            String summary = body.get();
            save(jobName, start, (System.nanoTime() - t0) / 1_000_000, "OK", summary);
        } catch (RuntimeException e) {
            save(jobName, start, (System.nanoTime() - t0) / 1_000_000, "FAILED",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /** Runs {@code invoker} as a manual ("Run now") run of a job. */
    public void runManually(Runnable invoker) {
        MANUAL.set(true);
        try {
            invoker.run();
        } finally {
            MANUAL.set(false);
        }
    }

    private void save(String jobName, Instant start, long durationMs, String result, String message) {
        boolean manual = MANUAL.get();
        try {
            independentTx.executeWithoutResult(s -> {
                jdbc.update("INSERT INTO scheduled_job_run (job_name, started_at, duration_ms, result, message, manual) VALUES (?, ?, ?, ?, ?, ?)",
                        jobName, Timestamp.from(start), durationMs, result,
                        message == null ? null : message.substring(0, Math.min(message.length(), 2000)), manual);
                // keep the history bounded: the latest 200 runs per job
                jdbc.update("""
                        DELETE FROM scheduled_job_run WHERE job_name = ? AND id NOT IN (
                            SELECT id FROM scheduled_job_run WHERE job_name = ? ORDER BY started_at DESC LIMIT 200)
                        """, jobName, jobName);
            });
        } catch (RuntimeException e) {
            log.warn("Could not record run of job {}: {}", jobName, e.getMessage());
        }
    }
}
