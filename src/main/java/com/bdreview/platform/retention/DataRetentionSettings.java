package com.bdreview.platform.retention;

import com.bdreview.platform.adminconfig.AdminConfigService;
import com.bdreview.platform.adminconfig.RetentionConfig;
import com.bdreview.platform.common.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Retention periods for {@link DataRetentionJob} (V69): the deployment default (application.yml
 * {@code retention.*}, env-configurable) unless an admin saved an override in admin_config
 * section RETENTION (System → Health → Data retention).
 */
@Component
public class DataRetentionSettings {

    /** Moderation and financial audit entries are kept at least this long, whatever the audit setting says. */
    public static final int PROTECTED_AUDIT_MIN_DAYS = 730;

    /**
     * Audit entity types that are neither moderation nor financial (content and configuration
     * housekeeping): only these follow a shorter audit-log setting. Every other type — including
     * any added later — is protected by {@link #PROTECTED_AUDIT_MIN_DAYS}.
     */
    public static final Set<String> UNPROTECTED_AUDIT_TYPES = Set.of(
            "CONTENT_PAGE", "NOTIFICATION_TEMPLATE", "PROMO_TEMPLATE", "COMMUNITY_TOPIC", "COMMUNITY_ANNOUNCEMENT",
            "COMMUNITY_SETTINGS", "BROADCAST", "SCHEDULED_JOB", "FEATURE_FLAG", "PLATFORM_SETTING");

    /** One editable period: its admin_config field, label, allowed range and env default. */
    public record Period(String key, String label, int min, int max, int defaultDays,
                         Function<RetentionConfig, Integer> getter) {
    }

    /** All periods in effect for one run. */
    public record Effective(int notificationsReadDays, int notificationsUnreadDays, int searchLogDays,
                            int loginEventsDays, int jobRunsDays, int auditLogDays, int softDeletedContentDays) {
        public int protectedAuditDays() {
            return Math.max(auditLogDays, PROTECTED_AUDIT_MIN_DAYS);
        }
    }

    private final AdminConfigService adminConfig;
    private final List<Period> periods;

    public DataRetentionSettings(AdminConfigService adminConfig,
                                 @Value("${retention.notifications-read-days}") int notificationsReadDays,
                                 @Value("${retention.notifications-unread-days}") int notificationsUnreadDays,
                                 @Value("${retention.search-log-days}") int searchLogDays,
                                 @Value("${retention.login-events-days}") int loginEventsDays,
                                 @Value("${retention.job-runs-days}") int jobRunsDays,
                                 @Value("${retention.audit-log-days}") int auditLogDays,
                                 @Value("${retention.soft-deleted-content-days}") int softDeletedContentDays) {
        this.adminConfig = adminConfig;
        this.periods = List.of(
                new Period("notificationsReadDays", "Read notifications", 7, 3650, notificationsReadDays,
                        RetentionConfig::getNotificationsReadDays),
                new Period("notificationsUnreadDays", "Unread notifications", 7, 3650, notificationsUnreadDays,
                        RetentionConfig::getNotificationsUnreadDays),
                new Period("searchLogDays", "Search log (older days kept as daily counts)", 7, 3650, searchLogDays,
                        RetentionConfig::getSearchLogDays),
                new Period("loginEventsDays", "Login events", 7, 3650, loginEventsDays,
                        RetentionConfig::getLoginEventsDays),
                new Period("jobRunsDays", "Scheduled job run history", 7, 3650, jobRunsDays,
                        RetentionConfig::getJobRunsDays),
                new Period("auditLogDays", "Admin audit log", 90, 3650, auditLogDays, RetentionConfig::getAuditLogDays),
                new Period("softDeletedContentDays", "Deleted community posts and comments", 7, 3650,
                        softDeletedContentDays, RetentionConfig::getSoftDeletedContentDays));
    }

    public List<Period> periods() {
        return periods;
    }

    public RetentionConfig overrides() {
        return adminConfig.retention();
    }

    public Effective effective() {
        RetentionConfig o = adminConfig.retention();
        int[] d = periods.stream().mapToInt(p -> p.getter().apply(o) != null ? p.getter().apply(o) : p.defaultDays()).toArray();
        return new Effective(d[0], d[1], d[2], d[3], d[4], d[5], d[6]);
    }

    /** Values in effect, keyed like {@link Period#key()} — for the admin page. */
    public Map<String, Integer> effectiveByKey() {
        RetentionConfig o = adminConfig.retention();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Period p : periods) {
            out.put(p.key(), p.getter().apply(o) != null ? p.getter().apply(o) : p.defaultDays());
        }
        return out;
    }

    /** Saves admin overrides; a blank field (null) goes back to the deployment default. */
    public void save(Map<String, Integer> values, String reason) {
        for (Period p : periods) {
            Integer v = values.get(p.key());
            if (v != null && (v < p.min() || v > p.max())) {
                throw new BadRequestException(p.label() + " must be between " + p.min() + " and " + p.max() + " days.");
            }
        }
        RetentionConfig c = new RetentionConfig();
        c.setNotificationsReadDays(values.get("notificationsReadDays"));
        c.setNotificationsUnreadDays(values.get("notificationsUnreadDays"));
        c.setSearchLogDays(values.get("searchLogDays"));
        c.setLoginEventsDays(values.get("loginEventsDays"));
        c.setJobRunsDays(values.get("jobRunsDays"));
        c.setAuditLogDays(values.get("auditLogDays"));
        c.setSoftDeletedContentDays(values.get("softDeletedContentDays"));
        adminConfig.saveRetention(c, reason);
    }
}
