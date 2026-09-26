package com.bdreview.platform.messaging;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    private final MessageService messageService;
    private final UserRepository userRepository;

    public MessageController(MessageService messageService, UserRepository userRepository) {
        this.messageService = messageService;
        this.userRepository = userRepository;
    }

    /** Consumer starts (or continues) a thread with a business — see spec §16. */
    @PostMapping
    public ResponseEntity<MessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
        Message message = messageService.send(CurrentUser.id(), request);
        return ResponseEntity.ok(MessageResponse.from(message, nameOf(message.getSenderUserId())));
    }

    /** Either party replies inside an existing thread — this is also how owners reply to reviews' DM channel. */
    @PostMapping("/threads/{threadId}/reply")
    public ResponseEntity<MessageResponse> reply(@PathVariable UUID threadId, @Valid @RequestBody ReplyRequest request) {
        Message message = messageService.reply(CurrentUser.id(), threadId, request.content());
        return ResponseEntity.ok(MessageResponse.from(message, nameOf(message.getSenderUserId())));
    }

    /** Consumer taps a quick-reply chip — posts the business's configured canned answer as if from the owner. */
    @PostMapping("/threads/{threadId}/auto-reply")
    public ResponseEntity<MessageResponse> triggerAutoReply(@PathVariable UUID threadId, @Valid @RequestBody AutoReplyTriggerRequest request) {
        Message message = messageService.triggerAutoReply(CurrentUser.id(), threadId, request.autoReplyId());
        return ResponseEntity.ok(MessageResponse.from(message, nameOf(message.getSenderUserId())));
    }

    /** Tap an emoji next to a message — toggles it off if already reacted with the same one. */
    @PostMapping("/{messageId}/reactions")
    public ResponseEntity<List<ReactionSummary>> react(@PathVariable UUID messageId, @Valid @RequestBody ReactRequest request) {
        return ResponseEntity.ok(messageService.react(CurrentUser.id(), messageId, request.emoji()));
    }

    @PostMapping("/threads/{threadId}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID threadId) {
        messageService.markRead(CurrentUser.id(), threadId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/threads/{threadId}")
    public ResponseEntity<PageResponse<MessageResponse>> history(@PathVariable UUID threadId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Page<Message> messages = messageService.history(threadId, page, size);
        UUID viewerId = CurrentUser.id();
        Map<UUID, String> names = namesOf(messages.getContent().stream().map(Message::getSenderUserId).toList());
        Map<UUID, List<ReactionSummary>> reactions = messageService.reactionSummariesFor(
                messages.getContent().stream().map(Message::getId).toList(), viewerId);
        return ResponseEntity.ok(PageResponse.of(messages.map(m ->
                MessageResponse.from(m, names.get(m.getSenderUserId()), reactions.getOrDefault(m.getId(), List.of())))));
    }

    @GetMapping("/threads/mine")
    public ResponseEntity<List<MessageThreadResponse>> myThreads() {
        UUID userId = CurrentUser.id();
        List<MessageThread> threads = messageService.myThreadsAsConsumer(userId);
        Map<UUID, String> names = namesOf(threads.stream().map(MessageThread::getConsumerUserId).toList());
        return ResponseEntity.ok(threads.stream()
                .map(t -> MessageThreadResponse.from(t, names.get(t.getConsumerUserId()), messageService.unreadCountFor(t.getId(), userId)))
                .toList());
    }

    @GetMapping("/threads/business-inbox")
    public ResponseEntity<List<MessageThreadResponse>> businessInbox() {
        CurrentUser.requireRole("BUSINESS_OWNER");
        UUID ownerId = CurrentUser.id();
        List<MessageThread> threads = messageService.threadsForMyBusinesses(ownerId);
        Map<UUID, String> names = namesOf(threads.stream().map(MessageThread::getConsumerUserId).toList());
        return ResponseEntity.ok(threads.stream()
                .map(t -> MessageThreadResponse.from(t, names.get(t.getConsumerUserId()), messageService.unreadCountFor(t.getId(), ownerId)))
                .toList());
    }

    private String nameOf(UUID userId) {
        return userRepository.findById(userId).map(User::getName).orElse(null);
    }

    private Map<UUID, String> namesOf(List<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> names.put(u.getId(), u.getName()));
        return names;
    }
}
