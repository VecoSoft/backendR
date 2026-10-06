package com.bdreview.platform.accountcontrol;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One login (or login refused because of a restriction) — shown on the admin user page (V63). */
@Entity
@Table(name = "user_login_event")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class UserLoginEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** APP (JWT login) / ADMIN (panel form login). */
    @Column(nullable = false, length = 10)
    private String channel;

    /** SUCCESS / RESTRICTED. */
    @Column(nullable = false, length = 12)
    private String outcome;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
