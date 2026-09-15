package com.bdreview.platform.qr;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One permanent QR per business (V1 — business-level only, no table/product/
 * receipt QR). The token is opaque and maps to {@code businessId}; resolution
 * always re-reads the business's current slug, so a later slug change never
 * breaks an already-printed QR.
 */
@Entity
@Table(name = "business_qr")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessQr {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false, unique = true)
    private UUID businessId;

    @Column(name = "qr_token", nullable = false, unique = true, length = 32)
    private String qrToken;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private QrStatus status = QrStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
