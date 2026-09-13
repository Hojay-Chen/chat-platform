package com.luxera.companion.conversation;

import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageView;

import java.util.Collections;
import java.util.List;

/**
 * The single translation point from chat's persistence entities to the DTOs the rest of the world
 * sees. Keeping it in one place means {@code messages} can grow columns without any other module
 * noticing, and it makes it obvious (one file to grep) that nothing outside chat-platform ever
 * holds a {@link Message}.
 */
public final class MessageViews {

    private MessageViews() {
    }

    public static MessageView toView(Message m) {
        if (m == null) return null;
        return MessageView.builder()
                .id(m.getId())
                .conversationId(m.getConversationId())
                .clientMessageId(m.getClientMessageId())
                .senderType(m.getSenderType())
                .content(m.getContent())
                .intent(m.getIntent())
                .emotion(m.getEmotion())
                .topic(m.getTopic())
                .messageKind(m.getMessageKind())
                .sessionId(m.getSessionId())
                .exchangeId(m.getExchangeId())
                .deliveryStatus(m.getDeliveryStatus())
                .proactive(m.isProactive())
                .metadata(m.getMetadata() == null ? Collections.emptyMap() : m.getMetadata())
                .createdAt(m.getCreatedAt())
                .build();
    }

    public static List<MessageView> toViews(List<Message> messages) {
        if (messages == null) return List.of();
        return messages.stream().map(MessageViews::toView).toList();
    }

    public static ConversationView toView(Conversation c) {
        if (c == null) return null;
        return ConversationView.builder()
                .id(c.getId())
                .userId(c.getUserId())
                .companionId(c.getCompanionId())
                .title(c.getTitle())
                .lastMessageAt(c.getLastMessageAt())
                .messageCount(c.getMessageCount())
                .summary(c.getSummary())
                .status(c.getStatus())
                .build();
    }
}
