package com.bdreview.platform.messaging;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.catalog.BusinessAutoReply;
import com.bdreview.platform.catalog.BusinessAutoReplyRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.RateLimitExceededException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spec §16: a consumer's direct inquiry to a business owner reuses the same
 * thread/infra as owner-reply-to-review (§7) — this service is that shared
 * channel. Direction is inferred from whether the sender is the business's
 * owner or not; a thread is always scoped to exactly one consumer + one business.
 */
@Service
public class MessageService {

    /** WhatsApp/Messenger's usual quick-reaction set — kept small and fixed rather than
     *  accepting arbitrary text, since this is meant to be a reaction, not a message. */
    private static final Set<String> ALLOWED_REACTIONS = Set.of("👍", "❤️", "😂", "😮", "😢", "🙏");

    private final MessageThreadRepository threadRepository;
    private final MessageRepository messageRepository;
    private final BusinessRepository businessRepository;
    private final BusinessAutoReplyRepository autoReplyRepository;
    private final MessageReactionRepository reactionRepository;
    private final long maxAutoRepliesPerHour;

    public MessageService(MessageThreadRepository threadRepository,
                           MessageRepository messageRepository,
                           BusinessRepository businessRepository,
                           BusinessAutoReplyRepository autoReplyRepository,
                           MessageReactionRepository reactionRepository,
                           @Value("${app.messaging.max-auto-replies-per-hour:20}") long maxAutoRepliesPerHour) {
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.businessRepository = businessRepository;
        this.autoReplyRepository = autoReplyRepository;
        this.reactionRepository = reactionRepository;
        this.maxAutoRepliesPerHour = maxAutoRepliesPerHour;
    }

    @Transactional
    public Message send(UUID senderUserId, SendMessageRequest request) {
        Business business = businessRepository.findById(request.businessId())
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        boolean senderIsOwner = business.getOwnerUserId().equals(senderUserId);
        UUID consumerUserId = senderIsOwner ? resolveOtherPartyOrThrow(business.getId(), senderUserId) : senderUserId;

        MessageThread thread = threadRepository.findByConsumerUserIdAndBusinessId(consumerUserId, business.getId())
                .orElseGet(() -> threadRepository.save(MessageThread.builder()
                        .consumerUserId(consumerUserId).businessId(business.getId()).build()));

        return messageRepository.save(Message.builder()
                .threadId(thread.getId()).senderUserId(senderUserId).content(request.content()).build());
    }

    private UUID resolveOtherPartyOrThrow(UUID businessId, UUID ownerUserId) {
        // Owner replying: they must specify which consumer thread via a separate endpoint (send-in-thread);
        // sending "cold" as an owner with no existing thread isn't a valid direction for this MVP channel.
        throw new ForbiddenException(
                "Owners can only reply within an existing thread — use POST /messages/threads/{threadId}/reply");
    }

    @Transactional
    public Message reply(UUID senderUserId, UUID threadId, String content) {
        MessageThread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResourceNotFoundException("Thread not found"));
        Business business = businessRepository.findById(thread.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        boolean isParticipant = thread.getConsumerUserId().equals(senderUserId)
                || business.getOwnerUserId().equals(senderUserId);
        if (!isParticipant) {
            throw new ForbiddenException("You are not part of this conversation");
        }

        return messageRepository.save(Message.builder()
                .threadId(threadId).senderUserId(senderUserId).content(content).build());
    }

    /**
     * A customer tapped a quick-reply chip — the question was already sent as a normal
     * message via send()/reply(); this posts the configured answer as if from the owner.
     * The answer content is always looked up server-side by {@code autoReplyId}, never
     * accepted from the request, so a customer can't forge an arbitrary "owner" message.
     */
    @Transactional
    public Message triggerAutoReply(UUID consumerUserId, UUID threadId, UUID autoReplyId) {
        MessageThread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResourceNotFoundException("Thread not found"));
        if (!thread.getConsumerUserId().equals(consumerUserId)) {
            throw new ForbiddenException("You are not part of this conversation");
        }
        Business business = businessRepository.findById(thread.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        BusinessAutoReply autoReply = autoReplyRepository.findById(autoReplyId)
                .orElseThrow(() -> new ResourceNotFoundException("Quick reply not found"));
        if (!autoReply.getBusinessId().equals(business.getId())) {
            throw new ResourceNotFoundException("Quick reply not found");
        }

        long recent = messageRepository.countByThreadIdAndSenderUserIdAndCreatedAtAfter(
                threadId, business.getOwnerUserId(), Instant.now().minus(1, ChronoUnit.HOURS));
        if (recent >= maxAutoRepliesPerHour) {
            throw new RateLimitExceededException("Too many auto-replies in this conversation recently — please try again later.");
        }

        return messageRepository.save(Message.builder()
                .threadId(threadId).senderUserId(business.getOwnerUserId()).content(autoReply.getAnswer()).build());
    }

    /** Tapping an emoji next to a message — same participant check as reply(). Tapping the
     *  emoji you already reacted with removes it; tapping a different one replaces it. */
    @Transactional
    public List<ReactionSummary> react(UUID userId, UUID messageId, String emoji) {
        if (!ALLOWED_REACTIONS.contains(emoji)) {
            throw new BadRequestException("Unsupported reaction.");
        }
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        MessageThread thread = threadRepository.findById(message.getThreadId())
                .orElseThrow(() -> new ResourceNotFoundException("Thread not found"));
        Business business = businessRepository.findById(thread.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        boolean isParticipant = thread.getConsumerUserId().equals(userId) || business.getOwnerUserId().equals(userId);
        if (!isParticipant) {
            throw new ForbiddenException("You are not part of this conversation");
        }

        Optional<MessageReaction> existing = reactionRepository.findByMessageIdAndUserId(messageId, userId);
        if (existing.isPresent() && existing.get().getEmoji().equals(emoji)) {
            reactionRepository.delete(existing.get());
        } else if (existing.isPresent()) {
            existing.get().setEmoji(emoji);
            reactionRepository.save(existing.get());
        } else {
            reactionRepository.save(MessageReaction.builder().messageId(messageId).userId(userId).emoji(emoji).build());
        }
        return reactionSummariesFor(messageId, userId);
    }

    public List<ReactionSummary> reactionSummariesFor(UUID messageId, UUID viewerId) {
        return summarize(reactionRepository.findByMessageId(messageId), viewerId);
    }

    /** Batched for history() — one query for the whole page instead of one per message. */
    public Map<UUID, List<ReactionSummary>> reactionSummariesFor(List<UUID> messageIds, UUID viewerId) {
        Map<UUID, List<MessageReaction>> byMessage = reactionRepository.findByMessageIdIn(messageIds).stream()
                .collect(Collectors.groupingBy(MessageReaction::getMessageId));
        Map<UUID, List<ReactionSummary>> result = new HashMap<>();
        for (Map.Entry<UUID, List<MessageReaction>> entry : byMessage.entrySet()) {
            result.put(entry.getKey(), summarize(entry.getValue(), viewerId));
        }
        return result;
    }

    private List<ReactionSummary> summarize(List<MessageReaction> reactions, UUID viewerId) {
        return reactions.stream()
                .collect(Collectors.groupingBy(MessageReaction::getEmoji))
                .entrySet().stream()
                .map(e -> new ReactionSummary(e.getKey(), e.getValue().size(),
                        e.getValue().stream().anyMatch(r -> r.getUserId().equals(viewerId))))
                .toList();
    }

    @Transactional
    public void markRead(UUID readerUserId, UUID threadId) {
        messageRepository.markThreadReadBy(threadId, readerUserId, Instant.now());
    }

    public Page<Message> history(UUID threadId, int page, int size) {
        return messageRepository.findByThreadIdOrderByCreatedAtAsc(
                threadId, PageRequest.of(page, PageRequestDefaults.clamp(size)));
    }

    public long unreadCountFor(UUID threadId, UUID readerUserId) {
        return messageRepository.countByThreadIdAndReadAtIsNullAndSenderUserIdNot(threadId, readerUserId);
    }

    public List<MessageThread> myThreadsAsConsumer(UUID consumerUserId) {
        return threadRepository.findByConsumerUserId(consumerUserId);
    }

    public List<MessageThread> threadsForMyBusinesses(UUID ownerUserId) {
        List<UUID> businessIds = businessRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId)
                .stream().map(Business::getId).toList();
        return threadRepository.findByBusinessIdIn(businessIds);
    }
}
