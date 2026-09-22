package com.bdreview.platform.offer;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's claim on an Offer — carries its own redemption lifecycle (claim
 * and redemption are 1:1 by nature, so this is one row, not two tables; see
 * V36's migration comment). redemptionCode is generated the same way
 * qr.QrService#generateUniqueToken already does (SecureRandom + retry on
 * collision), copied rather than shared since it's a small, isolated piece
 * of logic and QrService is business-QR-specific.
 */
@Entity
@Table(name = "offer_claim")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OfferClaim {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "redemption_code", nullable = false, unique = true, length = 16)
    private String redemptionCode;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OfferClaimStatus status = OfferClaimStatus.CLAIMED;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    /** The business owner/staff user who confirmed the redemption — audit trail, not shown to the claiming customer. */
    @Column(name = "redeemed_by_user_id")
    private UUID redeemedByUserId;

    @PrePersist
    void onCreate() {
        this.claimedAt = Instant.now();
    }
}
