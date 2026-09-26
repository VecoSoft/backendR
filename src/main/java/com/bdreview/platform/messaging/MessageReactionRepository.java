package com.bdreview.platform.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, UUID> {

    List<MessageReaction> findByMessageIdIn(List<UUID> messageIds);

    List<MessageReaction> findByMessageId(UUID messageId);

    Optional<MessageReaction> findByMessageIdAndUserId(UUID messageId, UUID userId);
}
