package com.luxera.companion.conversation;

import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.events.ChatEventTypes;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.event.CompanionEventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-process implementation of {@link ChatWorldPort} — the single seam through which the
 * digital-human platform reads and writes the chat world.
 *
 * <p>This is the <em>only</em> class in the chat platform that is allowed to hand chat entities
 * across the boundary, and it never does: everything leaves as a {@link MessageView} or a
 * {@link ConversationView}. When the two platforms are deployed as separate processes, the
 * bootstrap swaps this bean for the DHCP-over-WebSocket adapter and nothing else changes.
 */
@Slf4j
@Component
public class ChatWorldAdapter implements ChatWorldPort {

    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final CompanionEventBus eventBus;
    private final SessionManager sessionManager;
    private final ConversationThreadService threadService;

    public ChatWorldAdapter(ConversationService conversationService,
                            MessageRepository messageRepository,
                            ConversationRepository conversationRepository,
                            CompanionEventBus eventBus,
                            SessionManager sessionManager,
                            ConversationThreadService threadService) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.threadService = threadService;
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<MessageView> messages(String conversationId) {
        return MessageViews.toViews(conversationService.messages(conversationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageView> recentMessages(String conversationId, int limit) {
        return MessageViews.toViews(conversationService.recentMessages(conversationId, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MessageView> message(String messageId) {
        return messageRepository.findById(messageId).map(MessageViews::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageView> userMessagesSince(String companionId, LocalDateTime since) {
        return MessageViews.toViews(messageRepository.findUserMessagesSince(companionId, since));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageView> messagesBetween(String companionId, LocalDateTime since, LocalDateTime until) {
        return MessageViews.toViews(messageRepository.findMessagesBetween(companionId, since, until));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageView> recentByCompanionAndKind(String companionId, String kind, int limit) {
        return MessageViews.toViews(messageRepository.findRecentByCompanionIdAndMessageKind(
                companionId, kind, PageRequest.of(0, Math.max(1, limit))));
    }

    @Override
    @Transactional(readOnly = true)
    public long countByCompanionAndKindSince(String companionId, String kind, LocalDateTime since) {
        return messageRepository.countByCompanionIdAndMessageKindAndCreatedAtAfter(companionId, kind, since);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationView> conversation(String conversationId) {
        return conversationRepository.findById(conversationId).map(MessageViews::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationView> conversations(String userId, String companionId) {
        return conversationService.list(userId, companionId).stream().map(MessageViews::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationView> conversationsOf(String companionId) {
        return conversationRepository.findByCompanionIdOrderByLastMessageAtDesc(companionId)
                .stream().map(MessageViews::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationView> conversationFor(String userId, String companionId) {
        List<Conversation> list = conversationService.list(userId, companionId);
        return list.isEmpty() ? Optional.empty() : Optional.of(MessageViews.toView(list.get(0)));
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ConversationView ensureConversation(String userId, String companionId, String companionName) {
        return MessageViews.toView(conversationService.ensureConversation(userId, companionId, companionName));
    }

    @Override
    @Transactional
    public MessageView append(MessageAppendCommand command) {
        if (command == null || command.conversationId() == null) {
            throw new IllegalArgumentException("append 需要 conversationId");
        }
        if (command.content() == null || command.content().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        // 幂等键映射到 messages.client_message_id(同会话唯一), 重放命令不会重复发帖
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            Optional<Message> existing = messageRepository.findByConversationIdAndClientMessageId(
                    command.conversationId(), command.idempotencyKey());
            if (existing.isPresent()) {
                return MessageViews.toView(existing.get());
            }
        }
        Message saved = conversationService.addMessage(
                command.conversationId(), command.senderType(), command.content().trim(),
                command.intent(), command.emotion(), command.topic(),
                command.proactive(), command.messageKind(), command.sessionId(), command.exchangeId(),
                command.idempotencyKey());

        // 数字人说的每句话都必须出现在前端事件流里——否则气泡只有刷新才看得到
        conversationRepository.findById(saved.getConversationId()).ifPresent(conv ->
                eventBus.publish(conv.getCompanionId(), ChatEventTypes.COMPANION_MESSAGE, Map.of(
                        "messageId", saved.getId(),
                        "conversationId", saved.getConversationId(),
                        "content", saved.getContent(),
                        "senderType", saved.getSenderType(),
                        "messageKind", saved.getMessageKind() == null ? "NORMAL" : saved.getMessageKind())));
        return MessageViews.toView(saved);
    }

    @Override
    @Transactional
    public void markRead(String companionId, Collection<String> messageIds) {
        updateDeliveryStatus(companionId, messageIds, "READ");
    }

    @Override
    @Transactional
    public void updateDeliveryStatus(String companionId, Collection<String> messageIds, String status) {
        if (messageIds == null || messageIds.isEmpty()) return;
        for (String id : messageIds) {
            conversationService.updateDeliveryStatus(id, status);
        }
    }

    @Override
    @Transactional
    public void updatePerception(String messageId, String intent, String emotion, String topic) {
        if (messageId == null) return;
        messageRepository.findById(messageId).ifPresent(m -> {
            if (intent != null && !intent.isBlank()) m.setIntent(intent);
            if (emotion != null && !emotion.isBlank()) m.setEmotion(emotion);
            if (topic != null && !topic.isBlank()) m.setTopic(topic);
            messageRepository.save(m);
        });
    }

    @Override
    public void publishEvent(String companionId, String type, Map<String, Object> payload) {
        eventBus.publish(companionId, type, payload == null ? Map.of() : payload);
    }

    @Override
    @Transactional
    public void touchThread(String companionId, String conversationId, String topic, String emotion) {
        try {
            conversationRepository.findById(conversationId).ifPresent(conv ->
                    threadService.touch(conversationId, companionId, conv.getUserId(), topic, emotion,
                            LocalDateTime.now()));
        } catch (Exception e) {
            log.debug("会话线程 touch 失败: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void recordBoundary(String companionId, String conversationId, String type, String reason) {
        try {
            // SessionManager.boundary 需要会话的人类一侧, 只有 chat 知道那是谁
            conversationRepository.findById(conversationId).ifPresent(conv ->
                    sessionManager.boundary(conv.getUserId(), companionId, conversationId, type, reason));
        } catch (Exception e) {
            log.debug("记录会话边界失败: {}", e.getMessage());
        }
    }
}
