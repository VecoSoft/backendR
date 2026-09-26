package com.bdreview.platform.messaging;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One user's reaction to one message — at most one row per (message, user); tapping
 *  the same emoji again removes it, tapping a different one replaces it (see
 *  MessageService#react). Restricted to a small fixed emoji set, enforced in the service. */
@Entity
@Table(name = "message_reaction", uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "user_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MessageReaction {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String emoji;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
