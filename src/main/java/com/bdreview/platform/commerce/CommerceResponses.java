package com.bdreview.platform.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Response DTOs for the commerce endpoints. */
public final class CommerceResponses {

    private CommerceResponses() {
    }

    /** Full owner-facing settings. */
    public record CommerceSettingsResponse(
            UUID businessId,
            CommerceMode mode,
            boolean orderingEnabled,
            boolean pickupEnabled,
            boolean ownDeliveryEnabled,
            boolean bookingEnabled,
            boolean autoConfirmBookings,
            boolean acceptingOrders,
            String pauseReason,
            Integer defaultPrepMinutes,
            boolean paymentCashOnDelivery,
            boolean paymentPayAtBusiness,
            boolean hasDeliveryZones
    ) {
        public static CommerceSettingsResponse from(BusinessCommerceSettings s, boolean hasDeliveryZones) {
            return new CommerceSettingsResponse(
                    s.getBusinessId(), s.getMode(), s.isOrderingEnabled(), s.isPickupEnabled(),
                    s.isOwnDeliveryEnabled(), s.isBookingEnabled(), s.isAutoConfirmBookings(), s.isAcceptingOrders(),
                    s.getPauseReason(), s.getDefaultPrepMinutes(), s.isPaymentCashOnDelivery(), s.isPaymentPayAtBusiness(),
                    hasDeliveryZones);
        }
    }

    /**
     * The subset the public business page / checkout may see. Same shape today
     * as the owner view, but a dedicated record so future owner-only fields
     * never leak.
     */
    public record PublicCommerceView(
            CommerceMode mode,
            boolean orderingEnabled,
            boolean pickupEnabled,
            boolean ownDeliveryEnabled,
            boolean bookingEnabled,
            boolean acceptingOrders,
            String pauseReason,
            boolean paymentCashOnDelivery,
            boolean paymentPayAtBusiness,
            boolean hasDeliveryZones
    ) {
        public static PublicCommerceView from(BusinessCommerceSettings s, boolean hasDeliveryZones) {
            return new PublicCommerceView(
                    s.getMode(), s.isOrderingEnabled(), s.isPickupEnabled(), s.isOwnDeliveryEnabled(),
                    s.isBookingEnabled(), s.isAcceptingOrders(), s.isAcceptingOrders() ? null : s.getPauseReason(),
                    s.isPaymentCashOnDelivery(), s.isPaymentPayAtBusiness(), hasDeliveryZones);
        }
    }

    public record DeliveryQuoteResponse(
            boolean deliverable,
            double distanceKm,
            UUID zoneId,
            String zoneName,
            BigDecimal deliveryFee,
            BigDecimal minimumOrderAmount,
            Integer estimatedDeliveryMinutes,
            Double maxDeliveryKm
    ) {
        public static DeliveryQuoteResponse notDeliverable(double distanceKm, Double maxDeliveryKm) {
            return new DeliveryQuoteResponse(false, round2(distanceKm), null, null, null, null, null, maxDeliveryKm);
        }

        public static DeliveryQuoteResponse deliverable(double distanceKm, DeliveryZone z) {
            return new DeliveryQuoteResponse(true, round2(distanceKm), z.getId(), z.getName(),
                    z.getDeliveryFee(), z.getMinimumOrderAmount(), z.getEstimatedDeliveryMinutes(), null);
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }

    public record OrderItemResponse(
            UUID id,
            OrderItemSource sourceType,
            UUID sourceItemId,
            String itemName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal totalPrice
    ) {
        public static OrderItemResponse from(BusinessOrderItem i) {
            return new OrderItemResponse(i.getId(), i.getSourceType(), i.getSourceItemId(),
                    i.getItemNameSnapshot(), i.getUnitPriceSnapshot(), i.getQuantity(), i.getTotalPrice());
        }
    }

    public record OrderStatusEventResponse(OrderStatus fromStatus, OrderStatus toStatus, String note, Instant at) {
        public static OrderStatusEventResponse from(OrderStatusEvent e) {
            return new OrderStatusEventResponse(e.getFromStatus(), e.getToStatus(), e.getNote(), e.getCreatedAt());
        }
    }

    public record OrderResponse(
            UUID id,
            String orderNumber,
            UUID businessId,
            String businessName,
            String businessSlug,
            /** The business's own contact number — shown on the order so the customer can call about pickup/delivery. */
            String businessPhone,
            /** "{area}, {city}" — the business has no separate street-address field; this is its location, same as the public page. */
            String businessAddress,
            UUID customerUserId,
            OrderStatus status,
            FulfillmentType fulfillmentType,
            BigDecimal subtotal,
            BigDecimal deliveryFee,
            BigDecimal discountAmount,
            BigDecimal totalAmount,
            PaymentMethod paymentMethod,
            PaymentStatus paymentStatus,
            String customerName,
            String deliveryAddress,
            Double deliveryLatitude,
            Double deliveryLongitude,
            BigDecimal deliveryDistanceKm,
            String customerNote,
            String rejectionReason,
            /** Set once the owner accepts the order — null before that (see BusinessOrder#estimatedReadyAt). */
            Instant estimatedReadyAt,
            Instant createdAt,
            List<OrderItemResponse> items,
            List<OrderStatusEventResponse> timeline
    ) {
    }

    public record BookingStatusEventResponse(BookingStatus fromStatus, BookingStatus toStatus, String note, Instant at) {
        public static BookingStatusEventResponse from(BookingStatusEvent e) {
            return new BookingStatusEventResponse(e.getFromStatus(), e.getToStatus(), e.getNote(), e.getCreatedAt());
        }
    }

    public record BookingResponse(
            UUID id,
            String bookingNumber,
            UUID businessId,
            String businessName,
            String businessSlug,
            UUID customerUserId,
            BookingStatus status,
            UUID serviceId,
            String serviceName,
            UUID staffId,
            String staffName,
            LocalDate preferredDate,
            LocalTime preferredTime,
            String customerName,
            String customerNote,
            String rejectionReason,
            Instant createdAt,
            List<BookingStatusEventResponse> timeline,
            boolean autoConfirmed,
            Instant startedAt
    ) {
    }

    /**
     * Live queue position/ETA for a CONFIRMED booking today (see req: "2 জনের পরে
     * আপনার পালা"). {@code applicable} is false for anything else — a future date,
     * or a booking not (yet) CONFIRMED — and the frontend just shows the plain
     * date/time in that case.
     */
    public record QueueStatusResponse(
            boolean applicable,
            Integer position,
            Integer estimatedWaitMinutes,
            String currentlyServingService
    ) {
        public static QueueStatusResponse notApplicable() {
            return new QueueStatusResponse(false, null, null, null);
        }
    }
}
