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

    /** V67 editable notification texts (System → Notifications → Templates) — setter-injected. */
    private com.bdreview.platform.notification.NotificationTemplateService templates;

    @org.springframework.beans.factory.annotation.Autowired
    void setTemplates(com.bdreview.platform.notification.NotificationTemplateService templates) {
        this.templates = templates;
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String businessNameFor(String table, UUID id) {
        try {
            return jdbc.queryForObject("SELECT b.name FROM " + table + " x JOIN business b ON b.id = x.business_id WHERE x.id = ?",
                    String.class, id);
        } catch (RuntimeException e) {
            return "the business";
        }
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
        if (templates != null) {
            templates.notify(customerUserId, com.bdreview.platform.notification.NotificationTemplateService.Key.ORDER_STATUS,
                    java.util.Map.of("orderNumber", orderNumber, "status", human(status), "businessName", businessNameFor("business_order", orderId)),
                    type, "ORDER", orderId);
            return;
        }
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
        if (templates != null) {
            templates.notify(customerUserId, com.bdreview.platform.notification.NotificationTemplateService.Key.BOOKING_STATUS,
                    java.util.Map.of("bookingNumber", bookingNumber, "status", human(status), "businessName", businessNameFor("business_booking", bookingId)),
                    type, "BOOKING", bookingId);
            return;
        }
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
