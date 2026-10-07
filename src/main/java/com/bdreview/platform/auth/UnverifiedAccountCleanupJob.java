package com.bdreview.platform.auth;

import com.bdreview.platform.health.JobRunRecorder;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Deletes e-mail sign-ups whose code was never entered within {@code app.auth.unverified-retention-days}
 * (7). Such an account never signed in, so it owns nothing but its codes and login events (both
 * deleted with it). An account that somehow gained other data is skipped, never force-deleted.
 */
@Component
public class UnverifiedAccountCleanupJob {

    public static final String JOB_NAME = "unverified-account-cleanup";
    private static final Logger log = LoggerFactory.getLogger(UnverifiedAccountCleanupJob.class);

    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;
    private final JobRunRecorder jobRuns;
    private final Duration retention;

    public UnverifiedAccountCleanupJob(UserRepository userRepository, JdbcTemplate jdbc, JobRunRecorder jobRuns,
                                       @Value("${app.auth.unverified-retention-days}") long retentionDays) {
        this.userRepository = userRepository;
        this.jdbc = jdbc;
        this.jobRuns = jobRuns;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(fixedRate = 6, timeUnit = TimeUnit.HOURS, initialDelay = 10)
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT30M")
    public void run() {
        jobRuns.trackWithSummary(JOB_NAME, () -> {
            List<UUID> ids = userRepository.findUnverifiedCreatedBefore(Instant.now().minus(retention), PageRequest.of(0, 500));
            int deleted = 0;
            int skipped = 0;
            for (UUID id : ids) {
                try {
                    // ON DELETE CASCADE removes its auth_email_code and user_login_event rows.
                    deleted += jdbc.update("DELETE FROM app_user WHERE id = ? AND account_status = 'EMAIL_UNVERIFIED'", id);
                } catch (RuntimeException e) {
                    skipped++;
                    log.warn("Unverified account {} not deleted (referenced elsewhere): {}", id, e.getMessage());
                }
            }
            return deleted == 0 && skipped == 0 ? "Nothing to delete"
                    : deleted + " unverified sign-up(s) deleted" + (skipped > 0 ? ", " + skipped + " skipped" : "");
        });
    }
}
