package com.luxera.companion.contracts.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

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
@Jacksonized   // G3: /internal HTTP 面上本类型第一次需要反序列化(agent-server 读回), @Builder 单独不生成 creator
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageView {

    private final String id;
    private final String conversationId;
    private final String clientMessageId;
    /** {@code user | companion | system} */
    private final String senderType;
    /**
     * 这条消息**是谁**发的 —— {@code senderType} 只说"以什么身份", 这个说"哪一个"。
     *
     * <p>一对一里 {@code senderType} 就够用了, 所以这一列到 2026-09 才补上。群聊里它不够:
     * 三个成员都是 {@code user}, 光看 {@code senderType} 分不出是谁在说话。
     *
     * <p>{@code user} → users.id; {@code companion} → 该 agent 在聊天平台的账号 id
     * (三期之前是 companionId); {@code system} → 为空。
     *
     * <p><b>可以为 null</b>: 加列之前的历史消息没有这个值, 而 {@code ddl-auto: update}
     * 既不能给非空表加 NOT NULL 列, 也补不出老数据的值。消费方必须容忍 null。
     */
    private final String senderId;
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
