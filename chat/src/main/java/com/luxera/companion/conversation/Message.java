package com.luxera.companion.conversation;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "messages", indexes = @Index(name = "idx_messages_conv", columnList = "conversation_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_messages_client", columnNames = {"conversation_id", "client_message_id"}))
@Getter
@Setter
public class Message {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    /** 客户端幂等键(用户消息), 同会话内唯一; agent/系统消息为空 */
    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    /** user | companion | system */
    @Column(name = "sender_type", nullable = false, length = 16)
    private String senderType;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 32)
    private String intent;

    @Column(length = 32)
    private String emotion;

    @Column(length = 64)
    private String topic;

    @Column(name = "is_proactive", nullable = false)
    private boolean proactive = false;

    // ── 会话模型 ──────────────────────────
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "exchange_id", length = 36)
    private String exchangeId;

    /** NORMAL/SHORT_ACK/PROACTIVE/FOLLOW_UP/SYSTEM/TOOL_RESULT */
    @Column(name = "message_kind", length = 24)
    private String messageKind = "NORMAL";

    /** PENDING/DELIVERED/READ */
    @Column(name = "delivery_status", length = 16)
    private String deliveryStatus = "DELIVERED";

    @Convert(converter = StringMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
