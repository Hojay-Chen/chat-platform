package com.luxera.companion.contracts.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * V10 §44 — the only shape of a chat message the digital-human platform ever sees.
 *
 * <p>Deliberately a read-only projection: the chat platform owns the {@code messages} table and
 * nothing outside it may mutate a row through this type. Writes go through
 * {@link com.luxera.companion.contracts.spi.ChatWorldPort#append(MessageAppendCommand)}.
 *
 * <p>Bean-style getters (rather than record components) are intentional — the majority of the
 * digital-human codebase already read {@code Message} through {@code getX()}, so the split cost
 * one type change per call site instead of two hundred. {@link #createdAt} stays a
 * {@link LocalDateTime}: both platforms share one database and one JVM clock, and converting to
 * {@code Instant} would silently shift every historical timestamp.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageView {

    private final String id;
    private final String conversationId;
    private final String clientMessageId;
    /** {@code user | companion | system} */
    private final String senderType;
    private final String content;
    private final String intent;
    private final String emotion;
    private final String topic;
    private final String messageKind;
    private final String sessionId;
    private final String exchangeId;
    /** {@code PENDING | DELIVERED | READ | IGNORED | DEFERRED} */
    private final String deliveryStatus;
    private final boolean proactive;
    private final Map<String, Object> metadata;
    private final LocalDateTime createdAt;

    @JsonIgnore
    public boolean isFromUser() {
        return "user".equals(senderType);
    }

    @JsonIgnore
    public boolean isFromCompanion() {
        return "companion".equals(senderType);
    }

    /**
     * Convenience for the common case of "a message the digital human is about to write".
     * Mirrors {@code MessageView.of(conversationId, senderType, content, messageKind)} from the
     * pre-split API so call sites that only need the three core fields stay short.
     */
    public static MessageView of(String conversationId, String senderType, String content, String messageKind) {
        return MessageView.builder()
                .conversationId(conversationId)
                .senderType(senderType)
                .content(content)
                .messageKind(messageKind)
                .metadata(Collections.emptyMap())
                .build();
    }
}
