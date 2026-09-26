package com.bdreview.platform.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageResponse(
        UUID id, UUID threadId, UUID senderUserId, String senderName, String content, Instant readAt, Instant createdAt,
        List<ReactionSummary> reactions
) {
    /** A just-created message (send/reply/auto-reply) has no reactions yet. */
    public static MessageResponse from(Message m, String senderName) {
        return from(m, senderName, List.of());
    }

    public static MessageResponse from(Message m, String senderName, List<ReactionSummary> reactions) {
        return new MessageResponse(m.getId(), m.getThreadId(), m.getSenderUserId(), senderName, m.getContent(),
                m.getReadAt(), m.getCreatedAt(), reactions);
    }
}
