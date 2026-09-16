package com.luxera.companion.conversation;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import com.luxera.companion.event.CompanionEventBus;
import com.luxera.companion.event.CompanionEventType;
import com.luxera.companion.outbox.OutboxPublisher;
import com.luxera.companion.simulator.server.SimulatorWebSocketController;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * §十一~§十四 Chat Core: 用户消息的唯一真相源。
 *
 * <p>职责链: Controller → MessageCoreService(同步落库) → 事件总线 + Outbox → 数字人平台(异步)。
 *
 * <p>关键保证:
 * <ol>
 *   <li><b>同步落库</b> —— 用户消息在 HTTP 请求事务内写入 messages 表, 返回 canonical messageId。
 *       刷新页面、服务器重启、对方崩溃都不丢消息。</li>
 *   <li><b>clientMessageId 幂等</b> —— 同会话内重复提交同一 clientMessageId 直接返回已存在消息。</li>
 *   <li><b>事务提交后才通知</b> —— 数字人看到的必然已落库。</li>
 *   <li><b>对方的认知不参与请求生命周期</b> —— 用户发送永不被对方阻塞。</li>
 * </ol>
 *
 * <p>V10 §2: 本类不知道对面是数字人。它只通过 {@link CompanionDirectoryPort} 知道三件事:
 * 这个用户是否拥有这个 peer、peer 叫什么、以及"人类说话了"这一事实。回复(如果有)会以普通
 * 消息的形式异步出现在同一个会话里。
 */
@Slf4j
@Service
public class MessageCoreService {

    /** Message Lifecycle: 消息已投递(数字人平台可能尚未读) */
    public static final String DELIVERED = "DELIVERED";

    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final CompanionEventBus eventBus;
    private final CompanionDirectoryPort companionDirectory;
    private final OutboxPublisher outboxPublisher;
    private final SimulatorWebSocketController simulatorController;

    public MessageCoreService(ConversationService conversationService,
                              MessageRepository messageRepository,
                              CompanionEventBus eventBus,
                              CompanionDirectoryPort companionDirectory,
                              OutboxPublisher outboxPublisher,
                              SimulatorWebSocketController simulatorController) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.eventBus = eventBus;
        this.companionDirectory = companionDirectory;
        this.outboxPublisher = outboxPublisher;
        this.simulatorController = simulatorController;
    }

    /**
     * 用户发送消息: 同步持久化 + 幂等 + 事后通知数字人平台。
     *
     * @return 规范化结果(含全部 canonical 消息, 按发送顺序)
     */
    @Transactional
    public SendResult send(String userId, String companionId, String conversationId,
                           List<SendItem> items) {
        companionDirectory.requireOwned(userId, companionId);
        Conversation conv = conversationService.requireVisible(userId, conversationId);
        if (!conv.getCompanionId().equals(companionId)) {
            throw new IllegalArgumentException("会话与伴侣不匹配");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        List<Message> persisted = new ArrayList<>();
        List<Message> newMessages = new ArrayList<>();

        for (SendItem item : items) {
            if (item == null || item.getContent() == null || item.getContent().isBlank()) continue;
            String content = item.getContent().trim();

            // 幂等: 同会话同 clientMessageId → 返回已存在消息(不重复入库/不重复触发)
            if (item.getClientMessageId() != null && !item.getClientMessageId().isBlank()) {
                Message existing = messageRepository
                        .findByConversationIdAndClientMessageId(conversationId, item.getClientMessageId())
                        .orElse(null);
                if (existing != null) {
                    persisted.add(existing);
                    continue;
                }
            }

            // 同步落库(消息立即成为事实; 感知是对方的事, 由对方在收到通知后自己做)
            // senderId 显式传 userId: 真人消息的作者不推导 —— 群聊里"用户"不再是一个人
            Message m = conversationService.addMessage(conversationId, "user", userId, content,
                    null, null, null, false, null, null, null, item.getClientMessageId());
            persisted.add(m);
            newMessages.add(m);
        }

        if (persisted.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        // flush 是**为了 createdAt 不是空的**, 不是为了持久化 —— 落库本来就会在事务提交时发生。
        //
        // `Message.createdAt` 是 `@CreationTimestamp`, 而 Hibernate 在 **flush 时**才给它赋值,
        // 不是在 `save()` 时。所以不 flush 就去构造视图/发事件, 前端拿到的每条 canonical 消息
        // `createdAt` 都是 null(控制器把它写成空串), MESSAGE_CREATED 事件的 `at` 也是空串
        // —— 两个消费者都会看到"刚刚创建的消息没有时间"。
        //
        // 自己塞 `LocalDateTime.now()` 是不行的: 时间戳该由那一次写入决定, 应用层再写一遍就有
        // 两个"创建时间", 而它们不一致时没人知道该信哪个。flush 是一次往返, 一批连发只有一次。
        messageRepository.flush();

        for (Message m : newMessages) {
            // Outbox: 消息已持久化的事件(前端据此 temp → canonical)
            eventBus.publish(companionId, CompanionEventType.MESSAGE_CREATED, Map.of(
                    "messageId", m.getId(),
                    "conversationId", conversationId,
                    "clientMessageId", m.getClientMessageId() == null ? "" : m.getClientMessageId(),
                    "content", m.getContent(),
                    "status", DELIVERED,
                    "at", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString()));
        }

        if (!newMessages.isEmpty()) {
            final List<MessageView> toProcess = MessageViews.toViews(newMessages);
            final List<String> msgIds = newMessages.stream().map(Message::getId).toList();
            final Message lastMsg = newMessages.get(newMessages.size() - 1);

            // 数字人平台的通知必须等事务提交(它读库时必须能读到刚写的行)
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        notifyDigitalHuman(userId, companionId, conversationId, toProcess);
                        // V10 §63: 推送 WS 事件给在线 simulator(数字人的设备)
                        pushWsEvent(conversationId, companionId, msgIds);
                    }
                });
            } else {
                notifyDigitalHuman(userId, companionId, conversationId, toProcess);
                pushWsEvent(conversationId, companionId, msgIds);
            }

            // V10 §21.3 Outbox 兜底: 与消息落库同事务入队
            try {
                outboxPublisher.enqueue("msg-delivered-" + lastMsg.getId(), companionId,
                        "CHAT_MESSAGE_DELIVERED",
                        Map.of(
                                "userId", userId,
                                "companionId", companionId,
                                "conversationId", conversationId,
                                "messageIds", msgIds,
                                "source", "chat-platform",
                                "phase", "live",
                                "dedupKey", companionId + "-" + conversationId + "-" + lastMsg.getId() + "-live"));
            } catch (Exception ignored) {
            }
        }

        return new SendResult(DELIVERED, MessageViews.toViews(persisted));
    }

    /**
     * V10 §2.1 — fire-and-forget. The digital human decides on its own whether to perceive,
     * ignore, defer or reply; a reply arrives later through {@code ChatWorldPort.append}.
     */
    private void notifyDigitalHuman(String userId, String companionId, String conversationId,
                                    List<MessageView> messages) {
        try {
            companionDirectory.onUserMessage(userId, companionId, conversationId, messages);
        } catch (Exception e) {
            log.warn("通知数字人平台失败(已由 outbox 兜底): {}", e.getMessage());
        }
    }

    /** V10 §63: 推送 chat.message.delivered WS 事件给在线 simulator */
    private void pushWsEvent(String conversationId, String companionId, List<String> msgIds) {
        try {
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("topic", "chat.message.delivered")
                    .put("conversationId", conversationId)
                    .put("companionId", companionId);
            var ids = payload.putArray("messageIds");
            for (String id : msgIds) ids.add(id);
            SimulatorWebSocketController.publishEvent("chat.message.delivered", payload);
        } catch (Exception ignored) {
        }
    }

    /** 发送条目(前端乐观消息的幂等键) */
    @Data
    public static class SendItem {
        private String content;
        private String clientMessageId;
    }

    /** 规范化发送结果(离开 chat 平台的一律是视图, 不是实体) */
    @Data
    public static class SendResult {
        private final String status;
        private final List<MessageView> messages;

        public SendResult(String status, List<MessageView> messages) {
            this.status = status;
            this.messages = messages;
        }

        public MessageView last() {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }
    }
}
