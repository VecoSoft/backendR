package com.bdreview.platform.retention;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs and their locks. Every {@code @Scheduled} job also carries {@code @SchedulerLock}
 * (V69 {@code shedlock} table in Postgres), so with several API instances each run happens on
 * exactly one of them. Lock times come from the database clock, so instance clock drift doesn't
 * matter. "Run now" from System → Health goes through the same lock.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT1H")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbc) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc)
                .usingDbTime()
                .build());
    }

    /** {@code APP_SCHEDULING_ENABLED=false} for one-off containers (media migration) that must not run jobs. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
