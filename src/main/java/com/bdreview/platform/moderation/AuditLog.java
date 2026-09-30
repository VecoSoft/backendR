package com.bdreview.platform.moderation;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Spec §12. Full audit trail for every admin resolution (reports,
 * fake-review flags, business claims): who resolved it, when, what action
 * was taken.
 */
@Entity
@Table(name = "audit_log")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType; // REPORT / REVIEW / BUSINESS_CLAIM ...

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(nullable = false, length = 50)
    private String action; // APPROVED / REJECTED / HIDDEN / RESTORED ...

    @Column(name = "performed_by_admin", nullable = false)
    private UUID performedByAdmin;

    @Column(columnDefinition = "text")
    private String notes;

    // ---- V56: full admin-action trail ----

    /** ADMIN / MODERATOR / SYSTEM (scheduled jobs, auto-moderation). */
    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "before_json", columnDefinition = "text")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "text")
    private String afterJson;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
