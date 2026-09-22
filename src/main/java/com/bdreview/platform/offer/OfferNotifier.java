package com.bdreview.platform.offer;

import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Fires offer notifications off the request thread — a separate bean so {@code @Async} takes effect, same reasoning as community.CommunityNotifier. IN_APP only for now. */
@Component
public class OfferNotifier {

    private final NotificationService notifications;

    public OfferNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Async
    public void offerClaimed(UUID businessOwnerUserId, UUID offerId, String offerTitle) {
        notifications.create(businessOwnerUserId, NotificationType.OFFER_CLAIMED,
                "Your offer was claimed",
                "Someone claimed \"" + offerTitle + "\".",
                "offer", offerId, NotificationChannel.IN_APP);
    }

    @Async
    public void offerRedeemed(UUID claimantUserId, UUID offerId, String offerTitle) {
        notifications.create(claimantUserId, NotificationType.OFFER_REDEEMED,
                "Offer redeemed",
                "Your \"" + offerTitle + "\" offer was redeemed. Enjoy!",
                "offer", offerId, NotificationChannel.IN_APP);
    }

    @Async
    public void offerApproved(UUID businessOwnerUserId, UUID offerId, String offerTitle) {
        notifications.create(businessOwnerUserId, NotificationType.OFFER_APPROVED,
                "Offer approved",
                "Your offer \"" + offerTitle + "\" is now live.",
                "offer", offerId, NotificationChannel.IN_APP);
    }

    @Async
    public void offerRejected(UUID businessOwnerUserId, UUID offerId, String offerTitle, String reason) {
        notifications.create(businessOwnerUserId, NotificationType.OFFER_REJECTED,
                "Offer rejected",
                "Your offer \"" + offerTitle + "\" was rejected"
                        + (reason == null || reason.isBlank() ? "." : ": " + reason),
                "offer", offerId, NotificationChannel.IN_APP);
    }
}
