package com.bdreview.platform.listing;

import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * In-app notices for admin decisions (V65 ADMIN_NOTICE): an order/booking cancelled by support,
 * an offer ended or hidden, verification and protected-edit outcomes. Async and best-effort —
 * a failed notification never rolls back the admin action that triggered it.
 */
@Component
public class AdminNotifier {

    private static final Logger log = LoggerFactory.getLogger(AdminNotifier.class);

    private final NotificationService notifications;

    public AdminNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Async
    public void notify(UUID recipientUserId, String title, String body, String entityType, UUID entityId) {
        if (recipientUserId == null) {
            return;
        }
        try {
            notifications.create(recipientUserId, NotificationType.ADMIN_NOTICE, title, body, entityType, entityId,
                    NotificationChannel.IN_APP);
        } catch (RuntimeException e) {
            log.warn("Admin notice to {} failed: {}", recipientUserId, e.getMessage());
        }
    }
}
