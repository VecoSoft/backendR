package com.bdreview.platform.commerce;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per business that has configured commerce (1:1, PK = business id).
 * Absent until the owner turns something on — {@code CommerceSettingsService}
 * returns a transient SHOWCASE_ONLY default for everyone else.
 *
 * <p>{@code bookingEnabled} / {@code serviceRequestEnabled} are already here so
 * Phases C/D flip them without a schema change.
 */
@Entity
@Table(name = "business_commerce_settings")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessCommerceSettings {

    @Id
    @Column(name = "business_id")
    private UUID businessId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommerceMode mode = CommerceMode.SHOWCASE_ONLY;

    @Builder.Default
    @Column(name = "ordering_enabled", nullable = false)
    private boolean orderingEnabled = false;

    @Builder.Default
    @Column(name = "booking_enabled", nullable = false)
    private boolean bookingEnabled = false;

    /** When true, a placed booking skips owner approval and lands as CONFIRMED immediately. */
    @Builder.Default
    @Column(name = "auto_confirm_bookings", nullable = false)
    private boolean autoConfirmBookings = false;

    @Builder.Default
    @Column(name = "service_request_enabled", nullable = false)
    private boolean serviceRequestEnabled = false;

    @Builder.Default
    @Column(name = "pickup_enabled", nullable = false)
    private boolean pickupEnabled = false;

    @Builder.Default
    @Column(name = "own_delivery_enabled", nullable = false)
    private boolean ownDeliveryEnabled = false;

    @Builder.Default
    @Column(name = "accepting_orders", nullable = false)
    private boolean acceptingOrders = true;

    @Column(name = "pause_reason", length = 200)
    private String pauseReason;

    @Column(name = "default_prep_minutes")
    private Integer defaultPrepMinutes;

    @Builder.Default
    @Column(name = "payment_cash_on_delivery", nullable = false)
    private boolean paymentCashOnDelivery = true;

    @Builder.Default
    @Column(name = "payment_pay_at_business", nullable = false)
    private boolean paymentPayAtBusiness = true;

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

    /** A fresh transient default for a business that has never configured commerce. */
    public static BusinessCommerceSettings defaultsFor(UUID businessId) {
        return BusinessCommerceSettings.builder().businessId(businessId).build();
    }

    public boolean supportsPayment(PaymentMethod method) {
        return switch (method) {
            case CASH_ON_DELIVERY -> paymentCashOnDelivery;
            case PAY_AT_BUSINESS -> paymentPayAtBusiness;
        };
    }
}
