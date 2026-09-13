package com.bdreview.platform.commerce;

import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fires order notifications off the request thread — a separate bean (not a
 * self-injected proxy) so {@code @Async} takes effect. IN_APP only for the MVP;
 * SMS/email dispatch can be layered on later without touching callers.
 */
@Component
public class CommerceNotifier {

    private final NotificationService notifications;

    public CommerceNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Async
    public void newOrder(UUID ownerUserId, UUID orderId, String orderNumber, String customerName) {
        notifications.create(ownerUserId, NotificationType.NEW_ORDER,
                "New order " + orderNumber,
                customerName + " placed a new order. Review and accept it from your Orders page.",
                "ORDER", orderId, NotificationChannel.IN_APP);
    }

    @Async
    public void statusChanged(UUID customerUserId, UUID orderId, String orderNumber,
                              OrderStatus status, NotificationType type) {
        notifications.create(customerUserId, type,
                "Order " + orderNumber + " " + human(status),
                "Your order " + orderNumber + " is now " + human(status) + ".",
                "ORDER", orderId, NotificationChannel.IN_APP);
    }

    @Async
    public void newBooking(UUID ownerUserId, UUID bookingId, String bookingNumber, String customerName) {
        notifications.create(ownerUserId, NotificationType.NEW_BOOKING,
                "New booking request " + bookingNumber,
                customerName + " requested an appointment. Review and confirm it from your Bookings page.",
                "BOOKING", bookingId, NotificationChannel.IN_APP);
    }

    @Async
    public void bookingStatusChanged(UUID customerUserId, UUID bookingId, String bookingNumber,
                                     BookingStatus status, NotificationType type) {
        notifications.create(customerUserId, type,
                "Booking " + bookingNumber + " " + human(status),
                "Your booking " + bookingNumber + " is now " + human(status) + ".",
                "BOOKING", bookingId, NotificationChannel.IN_APP);
    }

    private static String human(OrderStatus s) {
        return s.name().toLowerCase().replace('_', ' ');
    }

    private static String human(BookingStatus s) {
        return s.name().toLowerCase().replace('_', ' ');
    }
}
