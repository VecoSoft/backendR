package com.bdreview.platform.notification;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.otp.SmsGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Generic notification store + best-effort dispatch. Callers that trigger a
 * notification from inside a request thread should do so from an {@code
 * @Async} method of their own (see ReportService) — create() itself makes an
 * external SMS-gateway call for SMS-channel notifications and must never run
 * on a request thread.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SmsGatewayService smsGatewayService;
    /** Setter-injected (optional) so unit tests can build the service without the flag store. */
    private com.bdreview.platform.features.FeatureFlagService features;

    public NotificationService(NotificationRepository notificationRepository,
                                UserRepository userRepository,
                                SmsGatewayService smsGatewayService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.smsGatewayService = smsGatewayService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFeatures(com.bdreview.platform.features.FeatureFlagService features) {
        this.features = features;
    }

    /**
     * V70: the app no longer collects phone numbers and SMS is off (PHONE_OTP flag), so an SMS
     * notification is kept as an in-app one instead of being lost: whenever SMS is off or the
     * recipient has no phone number.
     */
    private NotificationChannel effectiveChannel(UUID recipientUserId, NotificationChannel requested) {
        if (requested != NotificationChannel.SMS) {
            return requested;
        }
        boolean smsOn = features != null && features.isEnabled(com.bdreview.platform.features.PlatformFeature.PHONE_OTP);
        boolean hasPhone = userRepository.findById(recipientUserId).map(u -> u.getPhoneNumber() != null).orElse(false);
        return smsOn && hasPhone ? NotificationChannel.SMS : NotificationChannel.IN_APP;
    }

    /** Persists the notification, then attempts delivery immediately (IN_APP has no delivery step to attempt). */
    @Transactional
    public Notification create(UUID recipientUserId, NotificationType type, String title, String body,
                                String relatedEntityType, UUID relatedEntityId, NotificationChannel channel) {
        Notification notification = notificationRepository.save(Notification.builder()
                .recipientUserId(recipientUserId)
                .type(type)
                .title(title)
                .body(body)
                .relatedEntityType(relatedEntityType)
                .relatedEntityId(relatedEntityId)
                .channel(effectiveChannel(recipientUserId, channel))
                .build());

        deliver(notification);
        return notification;
    }

    private void deliver(Notification notification) {
        if (notification.getChannel() != NotificationChannel.SMS) {
            // IN_APP: the persisted row itself is the delivery — nothing external to do.
            notificationRepository.updateStatus(notification.getId(), NotificationStatus.SENT);
            return;
        }
        try {
            User recipient = userRepository.findById(notification.getRecipientUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("Notification recipient not found"));
            smsGatewayService.sendMessage(recipient.getPhoneNumber(), notification.getBody());
            notificationRepository.updateStatus(notification.getId(), NotificationStatus.SENT);
        } catch (Exception ex) {
            log.warn("SMS notification {} delivery failed: {}", notification.getId(), ex.getMessage());
            notificationRepository.updateStatus(notification.getId(), NotificationStatus.FAILED);
        }
    }

    public Page<Notification> myNotifications(UUID userId, Pageable pageable) {
        return notificationRepository.findByRecipientUserIdAndChannelOrderByCreatedAtDesc(
                userId, NotificationChannel.IN_APP, pageable);
    }

    public long unreadCount(UUID userId) {
        return notificationRepository.countByRecipientUserIdAndChannelAndStatusNot(
                userId, NotificationChannel.IN_APP, NotificationStatus.READ);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findByIdAndRecipientUserId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        notificationRepository.markRead(notification.getId(), NotificationStatus.READ, Instant.now());
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepository.markAllRead(userId, NotificationChannel.IN_APP, NotificationStatus.READ, Instant.now());
    }
}
