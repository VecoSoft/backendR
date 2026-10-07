package com.bdreview.platform.notification;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.health.JobRunRecorder;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.otp.LoggingSmsGatewayService;
import com.bdreview.platform.otp.SmsGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Admin broadcasts (V67, System → Notifications): one in-app notification to every user, every
 * business owner, or users/owners filtered by area and category — sent now or at a scheduled time
 * (cancellable until then). SMS goes out too only when a real SMS gateway is configured; the
 * default dev gateway only logs, so the option is unavailable there. History shows delivered
 * (notifications created) and read counts.
 */
@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    public enum Audience {
        ALL_USERS("All users (personal accounts)"),
        ALL_OWNERS("All business owners"),
        USERS("Users active in an area / category"),
        OWNERS("Owners with a listing in an area / category");

        private final String label;

        Audience(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Draft(String title, String body, Audience audience, UUID areaId, UUID categoryId,
                        boolean sendSms, Instant scheduledAt) {
    }

    private final JdbcTemplate jdbc;
    private final AuditLogService auditLogService;
    private final SmsGatewayService sms;
    private final JobRunRecorder jobRuns;

    public BroadcastService(JdbcTemplate jdbc, AuditLogService auditLogService, SmsGatewayService sms, JobRunRecorder jobRuns) {
        this.jdbc = jdbc;
        this.auditLogService = auditLogService;
        this.sms = sms;
        this.jobRuns = jobRuns;
    }

    /** False with the default logging-only gateway — SMS broadcasts then aren't offered. */
    public boolean smsAvailable() {
        return !(sms instanceof LoggingSmsGatewayService);
    }

    // ---------------------------------------------------------------- audience

    /** SQL selecting the recipient user ids (and phone numbers) for an audience; params appended to {@code args}. */
    private String audienceSql(Audience audience, UUID areaId, UUID categoryId, List<Object> args) {
        String businessFilter = "b.deleted_at IS NULL"
                + (areaId != null ? " AND b.area_id = ?" : "")
                + (categoryId != null ? " AND b.category_id = ?" : "");
        return switch (audience) {
            case ALL_USERS -> "SELECT u.id, u.phone_number FROM app_user u WHERE u.role = 'CONSUMER'";
            case ALL_OWNERS -> "SELECT u.id, u.phone_number FROM app_user u WHERE u.role = 'BUSINESS_OWNER'";
            case OWNERS -> {
                addFilterArgs(args, areaId, categoryId);
                yield "SELECT u.id, u.phone_number FROM app_user u WHERE u.role = 'BUSINESS_OWNER' AND u.id IN "
                        + "(SELECT b.owner_user_id FROM business b WHERE " + businessFilter + ")";
            }
            case USERS -> {
                // "Active in" = reviewed, ordered or booked at a listing in that area/category.
                addFilterArgs(args, areaId, categoryId);
                addFilterArgs(args, areaId, categoryId);
                addFilterArgs(args, areaId, categoryId);
                yield "SELECT u.id, u.phone_number FROM app_user u WHERE u.role = 'CONSUMER' AND u.id IN ("
                        + "SELECT r.user_id FROM review r JOIN business b ON b.id = r.business_id WHERE r.deleted_at IS NULL AND " + businessFilter
                        + " UNION SELECT o.customer_user_id FROM business_order o JOIN business b ON b.id = o.business_id WHERE " + businessFilter
                        + " UNION SELECT k.customer_user_id FROM business_booking k JOIN business b ON b.id = k.business_id WHERE " + businessFilter
                        + ")";
            }
        };
    }

    private static void addFilterArgs(List<Object> args, UUID areaId, UUID categoryId) {
        if (areaId != null) {
            args.add(areaId);
        }
        if (categoryId != null) {
            args.add(categoryId);
        }
    }

    public long audienceSize(Audience audience, UUID areaId, UUID categoryId) {
        List<Object> args = new ArrayList<>();
        String sql = audienceSql(audience, areaId, categoryId, args);
        return jdbc.queryForObject("SELECT count(*) FROM (" + sql + ") a", Long.class, args.toArray());
    }

    // ---------------------------------------------------------------- create / cancel

    @Transactional
    public UUID create(Draft d, String reason) {
        String why = requireReason(reason);
        validate(d);
        if (d.sendSms() && !smsAvailable()) {
            throw new BadRequestException("No SMS gateway is configured — send it in-app only.");
        }
        UUID id = UUID.randomUUID();
        boolean later = d.scheduledAt() != null && d.scheduledAt().isAfter(Instant.now().plusSeconds(30));
        jdbc.update("""
                INSERT INTO broadcast (id, title, body, audience, area_id, category_id, send_sms, scheduled_at, status, reason, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?, ?)
                """, id, d.title().trim(), d.body().trim(), d.audience().name(), d.areaId(), d.categoryId(), d.sendSms(),
                later ? Timestamp.from(d.scheduledAt()) : Timestamp.from(Instant.now()), why, CurrentUser.idOrNull());
        auditLogService.record("BROADCAST", id, later ? "BROADCAST_SCHEDULED" : "BROADCAST_SENT", why, null,
                Map.of("title", d.title(), "audience", d.audience().name(),
                        "area", String.valueOf(d.areaId()), "category", String.valueOf(d.categoryId()),
                        "scheduledAt", later ? d.scheduledAt().toString() : "now"));
        if (!later) {
            send(id);
        }
        return id;
    }

    @Transactional
    public void cancel(UUID id, String reason) {
        String why = requireReason(reason);
        int n = jdbc.update("UPDATE broadcast SET status = 'CANCELLED', cancelled_by = ? WHERE id = ? AND status = 'SCHEDULED'",
                CurrentUser.idOrNull(), id);
        if (n == 0) {
            throw new BadRequestException("Only a broadcast that is still scheduled can be cancelled.");
        }
        auditLogService.record("BROADCAST", id, "BROADCAST_CANCELLED", why, Map.of("status", "SCHEDULED"), Map.of("status", "CANCELLED"));
    }

    // ---------------------------------------------------------------- delivery

    /** Sends one broadcast: an in-app notification per recipient (one INSERT … SELECT), then optional SMS. */
    @Transactional
    public int send(UUID id) {
        if (jdbc.update("UPDATE broadcast SET status = 'SENDING' WHERE id = ? AND status = 'SCHEDULED'", id) == 0) {
            return 0;
        }
        Map<String, Object> b = jdbc.queryForMap("SELECT * FROM broadcast WHERE id = ?", id);
        List<Object> args = new ArrayList<>(List.of(b.get("title"), b.get("body"), id));
        String sql = audienceSql(Audience.valueOf((String) b.get("audience")), (UUID) b.get("area_id"), (UUID) b.get("category_id"), args);
        int count = jdbc.update("""
                INSERT INTO notification (id, recipient_user_id, type, title, body, related_entity_type, related_entity_id, channel, status, created_at)
                SELECT uuid_generate_v4(), a.id, 'BROADCAST', ?, ?, 'BROADCAST', ?, 'IN_APP', 'SENT', now() FROM (""" + sql + ") a",
                args.toArray());
        int smsSent = 0;
        if (Boolean.TRUE.equals(b.get("send_sms")) && smsAvailable()) {
            List<Object> smsArgs = new ArrayList<>();
            String smsSql = audienceSql(Audience.valueOf((String) b.get("audience")), (UUID) b.get("area_id"), (UUID) b.get("category_id"), smsArgs);
            for (String phone : jdbc.queryForList("SELECT phone_number FROM (" + smsSql + ") a", String.class, smsArgs.toArray())) {
                try {
                    sms.sendMessage(phone, b.get("title") + ": " + b.get("body"));
                    smsSent++;
                } catch (RuntimeException e) {
                    log.warn("Broadcast SMS to {} failed: {}", phone, e.getMessage());
                }
            }
        }
        jdbc.update("UPDATE broadcast SET status = 'SENT', sent_at = now(), recipient_count = ?, sms_count = ? WHERE id = ?",
                count, smsSent, id);
        return count;
    }

    /** Every minute: send the broadcasts whose time has come. */
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES, initialDelay = 1)
    @SchedulerLock(name = "broadcast-dispatch", lockAtMostFor = "PT10M")
    public void dispatchDue() {
        jobRuns.track("broadcast-dispatch", () -> {
            for (UUID id : jdbc.queryForList("SELECT id FROM broadcast WHERE status = 'SCHEDULED' AND scheduled_at <= now()", UUID.class)) {
                try {
                    send(id);
                } catch (RuntimeException e) {
                    log.error("Broadcast {} failed", id, e);
                    jdbc.update("UPDATE broadcast SET status = 'SCHEDULED' WHERE id = ? AND status = 'SENDING'", id);
                    throw e;
                }
            }
        });
    }

    // ---------------------------------------------------------------- history

    public List<Map<String, Object>> history(int limit) {
        return jdbc.queryForList("""
                SELECT b.*, a.name AS area_name, c.name AS category_name,
                       (SELECT count(*) FROM notification n WHERE n.related_entity_type = 'BROADCAST' AND n.related_entity_id = b.id
                          AND (n.read_at IS NOT NULL OR n.status = 'READ')) AS read_count
                FROM broadcast b
                LEFT JOIN area a ON a.id = b.area_id
                LEFT JOIN category c ON c.id = b.category_id
                ORDER BY coalesce(b.sent_at, b.scheduled_at, b.created_at) DESC
                LIMIT ?
                """, limit);
    }

    // ----------------------------------------------------------------

    private static void validate(Draft d) {
        if (d.title() == null || d.title().isBlank() || d.title().length() > 140) {
            throw new BadRequestException("Title is required (max 140 characters).");
        }
        if (d.body() == null || d.body().isBlank() || d.body().length() > 1000) {
            throw new BadRequestException("Message is required (max 1000 characters).");
        }
        if (d.audience() == null) {
            throw new BadRequestException("Pick an audience.");
        }
        if ((d.audience() == Audience.USERS || d.audience() == Audience.OWNERS) && d.areaId() == null && d.categoryId() == null) {
            throw new BadRequestException("Pick an area and/or a category for a filtered audience.");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        return reason.trim();
    }
}
