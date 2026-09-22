package com.bdreview.platform.business;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only log of "add" reaction events, separate from {@link BusinessReaction}
 * (a current-state table whose rows are deleted on un-react). Never updated or
 * deleted — exists purely so a time-windowed count (e.g. "trending this week")
 * can be computed without disturbing the toggle semantics of BusinessReaction.
 */
@Entity
@Table(name = "business_reaction_event")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessReactionEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false, length = 10)
    private BusinessReactionType reactionType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
