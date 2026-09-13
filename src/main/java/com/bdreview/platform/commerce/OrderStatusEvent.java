package com.bdreview.platform.commerce;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Append-only audit row — one per status change (spec: "every transition recorded"). */
@Entity
@Table(name = "order_status_event")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OrderStatusEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24)
    private OrderStatus toStatus;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
