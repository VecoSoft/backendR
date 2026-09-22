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
                "New vote on your post",
                "Someone voted on your community post.",
                "community_post", postId, NotificationChannel.IN_APP);
    }

    @Async
    public void commentReplied(UUID commentAuthorUserId, UUID postId, UUID commentId) {
        notifications.create(commentAuthorUserId, NotificationType.COMMUNITY_COMMENT_REPLY,
                "New reply to your comment",
                "Someone replied to your comment on a community post.",
                "community_post_comment", commentId, NotificationChannel.IN_APP);
    }

    @Async
    public void bestAnswerMarked(UUID answerAuthorUserId, UUID postId, UUID commentId) {
        notifications.create(answerAuthorUserId, NotificationType.COMMUNITY_BEST_ANSWER,
                "Your answer was marked as best",
                "The question's author picked your answer as the best one.",
                "community_post_comment", commentId, NotificationChannel.IN_APP);
    }
}
