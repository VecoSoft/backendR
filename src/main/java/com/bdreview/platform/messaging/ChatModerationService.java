package com.bdreview.platform.messaging;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Chat moderation (V67). Conversations are private: a participant can report one from the chat
 * menu, and only then does it appear for admins. Staff never get a way to open a conversation
 * nobody reported. Actions on a report: warn the reported user, block them from sending
 * messages for a number of days, or dismiss — each audited with a reason.
 */
@Service
public class ChatModerationService {

    public static final List<String> REASONS = List.of("SPAM", "HARASSMENT", "SCAM", "INAPPROPRIATE", "OTHER");
    public static final List<Integer> BLOCK_DAYS = List.of(1, 3, 7, 30, 365);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AuditLogService audit;

    public ChatModerationService(JdbcTemplate jdbc, NotificationService notifications, AuditLogService audit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- participants

    /** Participant ids of a thread: [consumer, business owner]. */
    private UUID[] participants(UUID threadId) {
        List<UUID[]> rows = jdbc.query("""
                SELECT t.consumer_user_id, b.owner_user_id FROM message_thread t JOIN business b ON b.id = t.business_id
                WHERE t.id = ?
                """, (rs, i) -> new UUID[]{(UUID) rs.getObject(1), (UUID) rs.getObject(2)}, threadId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        return rows.get(0);
    }

    public void requireParticipant(UUID userId, UUID threadId) {
        UUID[] p = participants(threadId);
        if (!userId.equals(p[0]) && !userId.equals(p[1])) {
            throw new ForbiddenException("You are not part of this conversation");
        }
    }

    // ---------------------------------------------------------------- user side

    @Transactional
    public UUID report(UUID reporterId, UUID threadId, String reason, String details) {
        UUID[] p = participants(threadId);
        if (!reporterId.equals(p[0]) && !reporterId.equals(p[1])) {
            throw new ForbiddenException("You are not part of this conversation");
        }
        String r = reason == null ? "" : reason.trim().toUpperCase(Locale.ROOT);
        if (!REASONS.contains(r)) {
            throw new BadRequestException("Pick a reason.");
        }
        String d = details == null || details.isBlank() ? null : details.trim();
        if (d != null && d.length() > 1000) {
            throw new BadRequestException("Keep the details under 1000 characters.");
        }
        List<UUID> open = jdbc.queryForList(
                "SELECT id FROM chat_report WHERE thread_id = ? AND reporter_id = ? AND status = 'OPEN'", UUID.class, threadId, reporterId);
        if (!open.isEmpty()) {
            return open.get(0);
        }
        UUID reported = reporterId.equals(p[0]) ? p[1] : p[0];
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_report (id, thread_id, reporter_id, reported_user_id, reason, details) VALUES (?, ?, ?, ?, ?, ?)",
                id, threadId, reporterId, reported, r, d);
        return id;
    }

    /** Active messaging block for this user, if any. */
    public Optional<Instant> blockedUntil(UUID userId) {
        return jdbc.query("""
                SELECT max(ends_at) FROM messaging_block WHERE user_id = ? AND lifted_at IS NULL AND ends_at > now()
                """, (rs, i) -> rs.getTimestamp(1), userId).stream()
                .filter(Objects::nonNull).map(Timestamp::toInstant).findFirst();
    }

    public void requireCanMessage(UUID userId) {
        blockedUntil(userId).ifPresent(until -> {
            throw new ForbiddenException("You can't send messages until " + until.truncatedTo(ChronoUnit.MINUTES)
                    + " because a conversation you were part of was reported and reviewed.");
        });
    }

    // ---------------------------------------------------------------- admin side

    public List<Map<String, Object>> queue(String status) {
        String s = status == null || status.isBlank() ? "OPEN" : status;
        return jdbc.queryForList("""
                SELECT r.id, r.thread_id, r.reason, r.details, r.status, r.action, r.created_at, r.resolved_at,
                       rp.name AS reporter_name, ru.name AS reported_name, r.reported_user_id, b.name AS business_name,
                       (SELECT count(*) FROM chat_report x WHERE x.thread_id = r.thread_id) AS reports_on_thread
                FROM chat_report r
                JOIN app_user rp ON rp.id = r.reporter_id
                JOIN app_user ru ON ru.id = r.reported_user_id
                JOIN message_thread t ON t.id = r.thread_id
                JOIN business b ON b.id = t.business_id
                WHERE (? = 'ALL' OR r.status = ?)
                ORDER BY r.created_at DESC LIMIT 200
                """, s, s);
    }

    public long openCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chat_report WHERE status = 'OPEN'", Long.class);
        return n == null ? 0 : n;
    }

    public Map<String, Object> report(UUID reportId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.*, rp.name AS reporter_name, rp.phone_number AS reporter_phone,
                       ru.name AS reported_name, ru.phone_number AS reported_phone,
                       b.name AS business_name, t.consumer_user_id, b.owner_user_id, rb.name AS resolved_by_name
                FROM chat_report r
                JOIN app_user rp ON rp.id = r.reporter_id
                JOIN app_user ru ON ru.id = r.reported_user_id
                JOIN message_thread t ON t.id = r.thread_id
                JOIN business b ON b.id = t.business_id
                LEFT JOIN app_user rb ON rb.id = r.resolved_by
                WHERE r.id = ?
                """, reportId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Chat report not found");
        }
        return rows.get(0);
    }

    /**
     * The messages of a REPORTED conversation. A thread with no report is never readable here —
     * staff can only reach a conversation through a report id.
     */
    public List<Map<String, Object>> reportedThread(UUID reportId) {
        Map<String, Object> r = report(reportId);
        return jdbc.queryForList("""
                SELECT m.id, m.sender_user_id, u.name AS sender_name, m.content, m.created_at
                FROM message m JOIN app_user u ON u.id = m.sender_user_id
                WHERE m.thread_id = ? AND EXISTS (SELECT 1 FROM chat_report x WHERE x.thread_id = m.thread_id)
                ORDER BY m.created_at DESC LIMIT 200
                """, r.get("thread_id"));
    }

    public List<Map<String, Object>> blocksFor(UUID userId) {
        return jdbc.queryForList("""
                SELECT id, reason, ends_at, lifted_at, created_at, (lifted_at IS NULL AND ends_at > now()) AS active
                FROM messaging_block WHERE user_id = ? ORDER BY created_at DESC
                """, userId);
    }

    @Transactional
    public void warn(UUID reportId, String reason) {
        String why = reason(reason);
        Map<String, Object> r = requireOpen(reportId);
        UUID user = (UUID) r.get("reported_user_id");
        notifications.create(user, NotificationType.ADMIN_NOTICE, "Warning about your messages",
                "A conversation you're in was reported and reviewed. " + why
                        + " Repeated problems can lead to a messaging block.", "CHAT_REPORT", reportId, NotificationChannel.IN_APP);
        resolve(reportId, "ACTIONED", "WARN", why);
        audit.record("CHAT_REPORT", reportId, "CHAT_WARNED", why, Map.of("status", "OPEN"),
                Map.of("status", "ACTIONED", "action", "WARN", "user", user.toString()));
    }

    @Transactional
    public void block(UUID reportId, int days, String reason) {
        if (!BLOCK_DAYS.contains(days)) {
            throw new BadRequestException("Pick a block length.");
        }
        String why = reason(reason);
        Map<String, Object> r = requireOpen(reportId);
        UUID user = (UUID) r.get("reported_user_id");
        Instant ends = Instant.now().plus(days, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO messaging_block (id, user_id, reason, ends_at, report_id, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), user, why, Timestamp.from(ends), reportId, CurrentUser.id());
        notifications.create(user, NotificationType.ADMIN_NOTICE, "Messaging paused for " + days + (days == 1 ? " day" : " days"),
                "After a reported conversation was reviewed, you can't send messages until "
                        + ends.truncatedTo(ChronoUnit.MINUTES) + ". " + why, "CHAT_REPORT", reportId, NotificationChannel.IN_APP);
        resolve(reportId, "ACTIONED", "BLOCK", why);
        audit.record("CHAT_REPORT", reportId, "CHAT_SENDER_BLOCKED", why, Map.of("status", "OPEN"),
                Map.of("status", "ACTIONED", "action", "BLOCK", "user", user.toString(), "days", days));
    }

    @Transactional
    public void dismiss(UUID reportId, String reason) {
        String why = reason(reason);
        requireOpen(reportId);
        resolve(reportId, "DISMISSED", "DISMISS", why);
        audit.record("CHAT_REPORT", reportId, "CHAT_REPORT_DISMISSED", why, Map.of("status", "OPEN"), Map.of("status", "DISMISSED"));
    }

    @Transactional
    public void liftBlock(UUID blockId, String reason) {
        String why = reason(reason);
        int n = jdbc.update("UPDATE messaging_block SET lifted_at = now() WHERE id = ? AND lifted_at IS NULL", blockId);
        if (n == 0) {
            throw new BadRequestException("This block was already lifted.");
        }
        audit.record("MESSAGING_BLOCK", blockId, "MESSAGING_BLOCK_LIFTED", why, Map.of("lifted", false), Map.of("lifted", true));
    }

    // ----------------------------------------------------------------

    private Map<String, Object> requireOpen(UUID reportId) {
        Map<String, Object> r = report(reportId);
        if (!"OPEN".equals(r.get("status"))) {
            throw new BadRequestException("This report was already handled.");
        }
        return r;
    }

    private void resolve(UUID reportId, String status, String action, String note) {
        jdbc.update("""
                UPDATE chat_report SET status = ?, action = ?, resolution_note = ?, resolved_by = ?, resolved_at = now() WHERE id = ?
                """, status, action, note, CurrentUser.id(), reportId);
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        String r = reason.trim();
        if (r.length() > 1000) {
            throw new BadRequestException("The reason can be at most 1000 characters.");
        }
        return r;
    }
}
