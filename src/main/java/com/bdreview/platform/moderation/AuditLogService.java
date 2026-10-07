package com.bdreview.platform.moderation;

import com.bdreview.platform.common.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Thin wrapper so every admin action across packages logs consistently (spec §12). Since V56
 * every row also records the actor's role, the request IP and — via {@link #record} — a reason
 * plus before/after JSON snapshots of the target.
 */
@Service
public class AuditLogService {

    /** performed_by_admin is NOT NULL — scheduled jobs / auto-moderation log under this id with role SYSTEM. */
    public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    public void log(String entityType, UUID entityId, String action, UUID performedByAdmin, String notes) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId != null ? entityId : SYSTEM_ACTOR)
                .action(action)
                .performedByAdmin(performedByAdmin)
                .notes(notes)
                .reason(notes)
                .actorRole(currentActorRole())
                .ipAddress(currentIp())
                .build());
    }

    /**
     * Full-trail entry for the current request's actor. {@code before}/{@code after} are any
     * Jackson-serialisable snapshot (a Map is typical) — null when not applicable.
     */
    public AuditLog record(String entityType, UUID entityId, String action, String reason, Object before, Object after) {
        UUID actor = CurrentUser.idOrNull();
        return auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId != null ? entityId : SYSTEM_ACTOR)
                .action(action)
                .performedByAdmin(actor != null ? actor : SYSTEM_ACTOR)
                .notes(reason)
                .reason(reason)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .actorRole(actor != null ? currentActorRole() : "SYSTEM")
                .ipAddress(currentIp())
                .build());
    }

    /** For scheduled jobs / automatic actions that have no human actor. */
    public AuditLog recordSystem(String entityType, UUID entityId, String action, String reason, Object before, Object after) {
        return auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId != null ? entityId : SYSTEM_ACTOR)
                .action(action)
                .performedByAdmin(SYSTEM_ACTOR)
                .notes(reason)
                .reason(reason)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .actorRole("SYSTEM")
                .ipAddress(currentIp())
                .build());
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise audit snapshot", e);
            return String.valueOf(value);
        }
    }

    private static String currentActorRole() {
        if (CurrentUser.hasRole("ADMIN")) {
            return "ADMIN";
        }
        // V67 admin permission roles without ROLE_ADMIN
        if (CurrentUser.hasRole("ADMIN_STAFF")) {
            if (CurrentUser.hasRole("MODERATOR")) {
                return "MODERATOR";
            }
            return CurrentUser.hasAuthority("PERM_FINANCE") ? "FINANCE" : "SUPPORT";
        }
        if (CurrentUser.hasRole("MODERATOR")) {
            return "MODERATOR";
        }
        return CurrentUser.idOrNull() == null ? "SYSTEM" : "USER";
    }

    private static String currentIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        // Real client IP via Tomcat's RemoteIpValve (X-Forwarded-For trusted only from Caddy).
        String ip = request.getRemoteAddr();
        return ip != null && ip.length() > 64 ? ip.substring(0, 64) : ip;
    }
}
