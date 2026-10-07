package com.bdreview.platform.support;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.RateLimitExceededException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import com.bdreview.platform.photomod.PhotoModerationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Support inbox (V67). Users and owners open a ticket from Help → Contact support (category,
 * subject, message, optional screenshot). The screenshot goes through photo moderation as a
 * private SUPPORT photo — only the sender and staff can ever load it. Staff work the inbox:
 * status (open / pending / resolved), assignee, internal notes, and replies (which reach the user
 * as an in-app notification). Every staff action is audited.
 */
@Service
public class SupportService {

    public static final List<String> CATEGORIES = List.of("ACCOUNT", "ORDER", "BOOKING", "LISTING", "PAYMENT", "OTHER");
    public static final List<String> STATUSES = List.of("OPEN", "PENDING", "RESOLVED");
    private static final int MAX_OPEN_PER_DAY = 10;

    private final JdbcTemplate jdbc;
    private final PhotoModerationService photoModeration;
    private final NotificationService notifications;
    private final AuditLogService audit;

    public SupportService(JdbcTemplate jdbc, PhotoModerationService photoModeration,
                          NotificationService notifications, AuditLogService audit) {
        this.jdbc = jdbc;
        this.photoModeration = photoModeration;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- user side

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    private com.bdreview.platform.gallery.ObjectStorageClient storage;

    @org.springframework.beans.factory.annotation.Autowired
    void setStorage(com.bdreview.platform.gallery.ObjectStorageClient storage) {
        this.storage = storage;
    }

    public com.bdreview.platform.gallery.PreSignedUploadResponse screenshotUploadUrl(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_EXTENSIONS.contains(ext)) {
            throw new BadRequestException("Only image files are allowed (jpg, jpeg, png, webp, gif)");
        }
        String key = storage.buildObjectKey("support", UUID.randomUUID().toString(), filename);
        return new com.bdreview.platform.gallery.PreSignedUploadResponse(storage.presignPutUrl(key), key, storage.cdnUrlFor(key));
    }

    @Transactional
    public UUID open(UUID userId, String category, String subject, String message, String screenshotUrl) {
        String cat = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(cat)) {
            throw new BadRequestException("Pick a category.");
        }
        String subj = text(subject, 160, "Add a short subject.");
        String body = text(message, 4000, "Describe the problem.");
        String shot = null;
        if (screenshotUrl != null && !screenshotUrl.isBlank()) {
            if (PhotoModerationService.objectKeyOf(screenshotUrl.trim()) == null) {
                throw new BadRequestException("Upload the screenshot through the app.");
            }
            shot = screenshotUrl.trim();
        }
        Long recent = jdbc.queryForObject(
                "SELECT count(*) FROM support_ticket WHERE user_id = ? AND created_at > now() - interval '1 day'", Long.class, userId);
        if (recent != null && recent >= MAX_OPEN_PER_DAY) {
            throw new RateLimitExceededException("You've contacted support a lot today — we'll get back to your open requests first.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO support_ticket (id, user_id, category, subject, screenshot_url) VALUES (?, ?, ?, ?, ?)",
                id, userId, cat, subj, shot);
        addMessage(id, userId, false, false, body);
        if (shot != null) {
            photoModeration.admitSupportScreenshot(id, userId, shot);
        }
        return id;
    }

    public List<Map<String, Object>> mine(UUID userId) {
        return jdbc.queryForList("""
                SELECT t.id, t.category, t.subject, t.status, t.created_at, t.updated_at,
                       (SELECT count(*) FROM support_ticket_message m WHERE m.ticket_id = t.id AND m.from_staff AND NOT m.internal) AS staff_replies
                FROM support_ticket t WHERE t.user_id = ? ORDER BY t.updated_at DESC LIMIT 50
                """, userId);
    }

    /** The user's own ticket with its public thread (internal notes never included). */
    public Map<String, Object> mineDetail(UUID userId, UUID ticketId) {
        Map<String, Object> t = ticket(ticketId);
        if (!userId.equals(t.get("user_id"))) {
            throw new ResourceNotFoundException("Support request not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(t);
        out.remove("assignee_id");
        out.remove("assignee_name");
        out.put("messages", jdbc.queryForList("""
                SELECT id, from_staff, body, created_at FROM support_ticket_message
                WHERE ticket_id = ? AND NOT internal ORDER BY created_at
                """, ticketId));
        return out;
    }

    @Transactional
    public void userReply(UUID userId, UUID ticketId, String message) {
        Map<String, Object> t = ticket(ticketId);
        if (!userId.equals(t.get("user_id"))) {
            throw new ForbiddenException("Not your support request");
        }
        addMessage(ticketId, userId, false, false, text(message, 4000, "Write a message."));
        jdbc.update("UPDATE support_ticket SET status = 'OPEN', updated_at = now() WHERE id = ?", ticketId);
    }

    // ---------------------------------------------------------------- staff side

    public List<Map<String, Object>> inbox(String status, String category, String assignee, String q) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.category, t.subject, t.status, t.created_at, t.updated_at, t.user_id,
                       u.name AS user_name, u.phone_number, u.role AS user_role, t.assignee_id, a.name AS assignee_name,
                       (SELECT m.from_staff FROM support_ticket_message m WHERE m.ticket_id = t.id AND NOT m.internal
                        ORDER BY m.created_at DESC LIMIT 1) AS last_from_staff
                FROM support_ticket t JOIN app_user u ON u.id = t.user_id LEFT JOIN app_user a ON a.id = t.assignee_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && STATUSES.contains(status)) {
            sql.append(" AND t.status = ?");
            args.add(status);
        } else if (!"ALL".equals(status)) {
            sql.append(" AND t.status <> 'RESOLVED'");
        }
        if (category != null && CATEGORIES.contains(category)) {
            sql.append(" AND t.category = ?");
            args.add(category);
        }
        if ("me".equals(assignee)) {
            sql.append(" AND t.assignee_id = ?");
            args.add(CurrentUser.id());
        } else if ("none".equals(assignee)) {
            sql.append(" AND t.assignee_id IS NULL");
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (t.subject ILIKE ? OR u.name ILIKE ? OR u.phone_number LIKE ?)");
            String like = "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY CASE t.status WHEN 'OPEN' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END, t.updated_at DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Long> counts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String s : STATUSES) {
            m.put(s, 0L);
        }
        jdbc.query("SELECT status, count(*) AS n FROM support_ticket GROUP BY status",
                rs -> { m.put(rs.getString("status"), rs.getLong("n")); });
        return m;
    }

    public Map<String, Object> ticket(UUID ticketId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.*, u.name AS user_name, u.phone_number, u.role AS user_role, u.preferred_language,
                       a.name AS assignee_name
                FROM support_ticket t JOIN app_user u ON u.id = t.user_id LEFT JOIN app_user a ON a.id = t.assignee_id
                WHERE t.id = ?
                """, ticketId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Support request not found");
        }
        return rows.get(0);
    }

    /** Full thread for staff, internal notes included. */
    public List<Map<String, Object>> thread(UUID ticketId) {
        return jdbc.queryForList("""
                SELECT m.id, m.from_staff, m.internal, m.body, m.created_at, m.author_id, a.name AS author_name
                FROM support_ticket_message m LEFT JOIN app_user a ON a.id = m.author_id
                WHERE m.ticket_id = ? ORDER BY m.created_at
                """, ticketId);
    }

    /** The screenshot's moderation row (for the staff image proxy), if any. */
    public Optional<UUID> screenshotPhotoId(UUID ticketId) {
        return jdbc.query("""
                SELECT id FROM photo_moderation WHERE source_type = 'SUPPORT' AND source_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, (rs, i) -> (UUID) rs.getObject("id"), ticketId).stream().findFirst();
    }

    /** Reply to the user: visible in their request thread + an in-app SUPPORT_REPLY notification. */
    @Transactional
    public void reply(UUID ticketId, String message, String newStatus) {
        Map<String, Object> t = ticket(ticketId);
        String body = text(message, 4000, "Write a reply.");
        addMessage(ticketId, CurrentUser.id(), true, false, body);
        String status = newStatus != null && STATUSES.contains(newStatus) ? newStatus : "PENDING";
        jdbc.update("UPDATE support_ticket SET status = ?, updated_at = now(), assignee_id = coalesce(assignee_id, ?) WHERE id = ?",
                status, CurrentUser.id(), ticketId);
        UUID userId = (UUID) t.get("user_id");
        boolean bn = "bn".equals(t.get("preferred_language"));
        notifications.create(userId, NotificationType.SUPPORT_REPLY,
                bn ? "সাপোর্ট থেকে উত্তর: " + t.get("subject") : "Support replied: " + t.get("subject"),
                abbreviate(body, 300), "SUPPORT_TICKET", ticketId, NotificationChannel.IN_APP);
        audit.record("SUPPORT_TICKET", ticketId, "SUPPORT_REPLIED", abbreviate(body, 1000),
                Map.of("status", t.get("status")), Map.of("status", status));
    }

    @Transactional
    public void note(UUID ticketId, String message) {
        ticket(ticketId);
        String body = text(message, 4000, "Write a note.");
        addMessage(ticketId, CurrentUser.id(), true, true, body);
        jdbc.update("UPDATE support_ticket SET updated_at = now() WHERE id = ?", ticketId);
        audit.record("SUPPORT_TICKET", ticketId, "SUPPORT_NOTE_ADDED", abbreviate(body, 1000), null, null);
    }

    @Transactional
    public void setStatus(UUID ticketId, String status, String reason) {
        if (!STATUSES.contains(status)) {
            throw new BadRequestException("Unknown status.");
        }
        String why = reason(reason);
        Map<String, Object> t = ticket(ticketId);
        jdbc.update("UPDATE support_ticket SET status = ?, updated_at = now() WHERE id = ?", status, ticketId);
        audit.record("SUPPORT_TICKET", ticketId, "SUPPORT_STATUS_CHANGED", why,
                Map.of("status", t.get("status")), Map.of("status", status));
    }

    @Transactional
    public void assign(UUID ticketId, UUID assigneeId, String reason) {
        String why = reason(reason);
        Map<String, Object> t = ticket(ticketId);
        if (assigneeId != null) {
            Boolean staff = jdbc.queryForObject("SELECT role = 'ADMIN' FROM app_user WHERE id = ?", Boolean.class, assigneeId);
            if (!Boolean.TRUE.equals(staff)) {
                throw new BadRequestException("Tickets can only be assigned to admin staff.");
            }
        }
        jdbc.update("UPDATE support_ticket SET assignee_id = ?, updated_at = now() WHERE id = ?", assigneeId, ticketId);
        Map<String, Object> before = new HashMap<>();
        before.put("assignee", t.get("assignee_id"));
        Map<String, Object> after = new HashMap<>();
        after.put("assignee", assigneeId);
        audit.record("SUPPORT_TICKET", ticketId, "SUPPORT_ASSIGNED", why, before, after);
    }

    public List<Map<String, Object>> staff() {
        return jdbc.queryForList("SELECT id, name, admin_role FROM app_user WHERE role = 'ADMIN' ORDER BY name");
    }

    // ----------------------------------------------------------------

    private void addMessage(UUID ticketId, UUID authorId, boolean fromStaff, boolean internal, String body) {
        jdbc.update("INSERT INTO support_ticket_message (id, ticket_id, author_id, from_staff, internal, body) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), ticketId, authorId, fromStaff, internal, body);
    }

    private static String text(String value, int max, String emptyMessage) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(emptyMessage);
        }
        String v = value.trim();
        if (v.length() > max) {
            throw new BadRequestException("Keep it under " + max + " characters.");
        }
        return v;
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        return reason.trim().length() > 1000 ? reason.trim().substring(0, 1000) : reason.trim();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
