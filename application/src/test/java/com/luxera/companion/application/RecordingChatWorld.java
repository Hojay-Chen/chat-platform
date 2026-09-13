package com.luxera.companion.application;

import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one thing this module uses the chat platform for is {@code publishEvent} — the SSE feed that
 * makes a move appear on a human's screen. Everything else in {@link ChatWorldPort} belongs to the
 * chat platform and is deliberately <em>not</em> stubbed with a plausible-looking empty value:
 * each unsupported call throws, so if application code ever starts reading conversations the test
 * suite says so instead of quietly getting {@code List.of()}.
 */
public class RecordingChatWorld implements ChatWorldPort {

    private final List<Map<String, Object>> published = new ArrayList<>();
    private final List<String> publishedTypes = new ArrayList<>();

    /** 测试断言用: 发到前端的事件(按顺序)。 */
    public List<Map<String, Object>> published() {
        return List.copyOf(published);
    }

    public List<String> publishedTypes() {
        return List.copyOf(publishedTypes);
    }

    /** 这个 Bean 是单例, 记录会跨测试方法累积 —— 断言计数前必须清一次。 */
    public void clear() {
        published.clear();
        publishedTypes.clear();
    }

    @Override
    public void publishEvent(String companionId, String type, Map<String, Object> payload) {
        publishedTypes.add(type);
        published.add(payload);
    }

    // ── 应用平台用不到的读/写路径: 显式不支持, 不返回似是而非的空值 ──

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(
                "应用平台不该调用 ChatWorldPort." + method + " —— 会话与消息属于聊天平台");
    }

    @Override
    public List<MessageView> messages(String conversationId) {
        throw unsupported("messages");
    }

    @Override
    public List<MessageView> recentMessages(String conversationId, int limit) {
        throw unsupported("recentMessages");
    }

    @Override
    public Optional<MessageView> message(String messageId) {
        throw unsupported("message");
    }

    @Override
    public List<MessageView> userMessagesSince(String companionId, LocalDateTime since) {
        throw unsupported("userMessagesSince");
    }

    @Override
    public List<MessageView> messagesBetween(String companionId, LocalDateTime since, LocalDateTime until) {
        throw unsupported("messagesBetween");
    }

    @Override
    public List<MessageView> recentByCompanionAndKind(String companionId, String kind, int limit) {
        throw unsupported("recentByCompanionAndKind");
    }

    @Override
    public long countByCompanionAndKindSince(String companionId, String kind, LocalDateTime since) {
        throw unsupported("countByCompanionAndKindSince");
    }

    @Override
    public Optional<ConversationView> conversation(String conversationId) {
        throw unsupported("conversation");
    }

    @Override
    public List<ConversationView> conversations(String userId, String companionId) {
        throw unsupported("conversations");
    }

    @Override
    public List<ConversationView> conversationsOf(String companionId) {
        throw unsupported("conversationsOf");
    }

    @Override
    public Optional<ConversationView> conversationFor(String userId, String companionId) {
        throw unsupported("conversationFor");
    }

    @Override
    public ConversationView ensureConversation(String userId, String companionId, String companionName) {
        throw unsupported("ensureConversation");
    }

    @Override
    public MessageView append(MessageAppendCommand command) {
        throw unsupported("append");
    }

    @Override
    public void markRead(String companionId, Collection<String> messageIds) {
        throw unsupported("markRead");
    }

    @Override
    public void updateDeliveryStatus(String companionId, Collection<String> messageIds, String status) {
        throw unsupported("updateDeliveryStatus");
    }

    @Override
    public void updatePerception(String messageId, String intent, String emotion, String topic) {
        throw unsupported("updatePerception");
    }

    @Override
    public void recordBoundary(String companionId, String conversationId, String type, String reason) {
        throw unsupported("recordBoundary");
    }

    @Override
    public void touchThread(String companionId, String conversationId, String topic, String emotion) {
        throw unsupported("touchThread");
    }
}
