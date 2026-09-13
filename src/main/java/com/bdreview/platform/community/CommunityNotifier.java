package com.bdreview.platform.community;

import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fires community-feature notifications off the request thread — a separate
 * bean (not a self-injected proxy) so {@code @Async} takes effect, same
 * reasoning as commerce.CommerceNotifier. IN_APP only for now.
 */
@Component
public class CommunityNotifier {

    private final NotificationService notifications;

    public CommunityNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Async
    public void postMentioned(UUID businessOwnerUserId, UUID postId, String businessName) {
        notifications.create(businessOwnerUserId, NotificationType.COMMUNITY_POST_MENTION,
                "Your business was mentioned",
                "Someone mentioned \"" + businessName + "\" in a community post.",
                "community_post", postId, NotificationChannel.IN_APP);
    }

    @Async
    public void newComment(UUID postAuthorUserId, UUID postId) {
        notifications.create(postAuthorUserId, NotificationType.COMMUNITY_POST_COMMENT,
                "New comment on your post",
                "Someone commented on your community post.",
                "community_post", postId, NotificationChannel.IN_APP);
    }

    @Async
    public void newReaction(UUID postAuthorUserId, UUID postId) {
        notifications.create(postAuthorUserId, NotificationType.COMMUNITY_POST_REACTION,
                "New reaction on your post",
                "Someone reacted to your community post.",
                "community_post", postId, NotificationChannel.IN_APP);
    }
}
