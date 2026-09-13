package com.luxera.companion.contracts.api;

import java.util.Map;

/**
 * Command shape for {@link com.luxera.companion.contracts.spi.ChatWorldPort#append}. Carries
 * everything the chat platform needs to persist one message; {@code idempotencyKey} maps onto
 * {@code messages.client_message_id} so a retried WS command cannot double-post.
 *
 * @param conversationId target conversation
 * @param senderType     {@code user | companion | system}
 * @param content        message text
 * @param messageKind    NORMAL | SHORT_ACK | PROACTIVE | FOLLOW_UP | SYSTEM | TOOL_RESULT
 * @param sessionId      optional conversation-session id (continuity bookkeeping)
 * @param exchangeId     optional exchange id (ties a user message to its reply)
 * @param idempotencyKey optional dedup key, unique per conversation
 * @param proactive      whether the digital human initiated this message on its own
 * @param intent         optional perception result — the digital human's reading of the message
 * @param emotion        optional perception result
 * @param topic          optional perception result
 * @param metadata       optional free-form attributes
 */
public record MessageAppendCommand(
        String conversationId,
        String senderType,
        String content,
        String messageKind,
        String sessionId,
        String exchangeId,
        String idempotencyKey,
        boolean proactive,
        String intent,
        String emotion,
        String topic,
        Map<String, Object> metadata
) {

    public static MessageAppendCommand of(String conversationId, String senderType, String content) {
        return new MessageAppendCommand(conversationId, senderType, content, "NORMAL",
                null, null, null, false, null, null, null, null);
    }

    public MessageAppendCommand withKind(String kind) {
        return new MessageAppendCommand(conversationId, senderType, content, kind,
                sessionId, exchangeId, idempotencyKey, proactive, intent, emotion, topic, metadata);
    }

    public MessageAppendCommand withSession(String sessionId, String exchangeId) {
        return new MessageAppendCommand(conversationId, senderType, content, messageKind,
                sessionId, exchangeId, idempotencyKey, proactive, intent, emotion, topic, metadata);
    }

    public MessageAppendCommand withIdempotencyKey(String idempotencyKey) {
        return new MessageAppendCommand(conversationId, senderType, content, messageKind,
                sessionId, exchangeId, idempotencyKey, proactive, intent, emotion, topic, metadata);
    }

    public MessageAppendCommand withProactive(boolean proactive) {
        return new MessageAppendCommand(conversationId, senderType, content, messageKind,
                sessionId, exchangeId, idempotencyKey, proactive, intent, emotion, topic, metadata);
    }

    public MessageAppendCommand withPerception(String intent, String emotion, String topic) {
        return new MessageAppendCommand(conversationId, senderType, content, messageKind,
                sessionId, exchangeId, idempotencyKey, proactive, intent, emotion, topic, metadata);
    }

    public MessageAppendCommand withMetadata(Map<String, Object> metadata) {
        return new MessageAppendCommand(conversationId, senderType, content, messageKind,
                sessionId, exchangeId, idempotencyKey, proactive, intent, emotion, topic, metadata);
    }
}
