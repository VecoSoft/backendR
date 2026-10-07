package com.bdreview.platform.retention;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock (V69 {@code shedlock} table): a {@code @SchedulerLock} job runs on at most one API
 * instance at a time. Lock times come from the database clock, so instance clock drift doesn't matter.
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
}
