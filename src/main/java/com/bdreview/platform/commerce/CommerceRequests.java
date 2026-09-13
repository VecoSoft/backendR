package com.bdreview.platform.commerce;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Request bodies for the commerce endpoints — grouped, each a tiny record. */
public final class CommerceRequests {

    private CommerceRequests() {
    }

    /** Owner upsert of the whole settings block. */
    public record CommerceSettingsRequest(
            @NotNull CommerceMode mode,
            boolean orderingEnabled,
            boolean pickupEnabled,
            boolean ownDeliveryEnabled,
            boolean paymentCashOnDelivery,
            boolean paymentPayAtBusiness,
            @Min(0) @Max(600) Integer defaultPrepMinutes
    ) {
    }

    /** Owner toggle for the accepting/paused switch. */
    public record AcceptingOrdersRequest(
            boolean accepting,
            @Size(max = 200) String reason
    ) {
    }

    public record DeliveryZoneRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin("0.0") @Digits(integer = 3, fraction = 2) BigDecimal minDistanceKm,
            @NotNull @DecimalMin("0.01") @Digits(integer = 3, fraction = 2) BigDecimal maxDistanceKm,
            @NotNull @DecimalMin("0.0") @Digits(integer = 8, fraction = 2) BigDecimal deliveryFee,
            @DecimalMin("0.0") @Digits(integer = 8, fraction = 2) BigDecimal minimumOrderAmount,
            @Min(1) @Max(1440) Integer estimatedDeliveryMinutes,
            Boolean active
    ) {
    }

    public record ReorderZonesRequest(@NotEmpty List<UUID> orderedIds) {
    }

    /** Checkout preview — how far, which zone, what fee. */
    public record DeliveryQuoteRequest(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng
    ) {
    }

    public record PlaceOrderRequest(
            @NotNull FulfillmentType fulfillmentType,
            @NotNull PaymentMethod paymentMethod,
            @NotBlank @Size(max = 120) String customerName,
            @NotBlank @Size(max = 20) String customerPhone,
            @Size(max = 500) String deliveryAddress,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double deliveryLat,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double deliveryLng,
            @Size(max = 500) String customerNote,
            @NotEmpty @Size(max = 50) List<@Valid OrderLine> items
    ) {
        public record OrderLine(@NotNull UUID menuItemId, @Min(1) @Max(50) int quantity) {
        }
    }

    /** Owner status transition. */
    public record UpdateOrderStatusRequest(
            @NotNull OrderStatus status,
            @Size(max = 200) String note
    ) {
    }

    // ---- Booking (Phase C — Salon & Beauty) -------------------------------

    /** Owner toggle for online booking. Separate from CommerceSettingsRequest since none of its
     *  fulfilment/payment fields apply to an appointment. */
    public record BookingSettingsRequest(boolean bookingEnabled, boolean autoConfirmBookings) {
    }

    public record PlaceBookingRequest(
            @NotNull UUID serviceId,
            UUID staffId,
            @NotNull LocalDate preferredDate,
            @NotNull LocalTime preferredTime,
            @NotBlank @Size(max = 120) String customerName,
            @NotBlank @Size(max = 20) String customerPhone,
            @Size(max = 500) String customerNote
    ) {
    }

    /** Owner status transition for a booking. */
    public record UpdateBookingStatusRequest(
            @NotNull BookingStatus status,
            @Size(max = 200) String note
    ) {
    }
}
