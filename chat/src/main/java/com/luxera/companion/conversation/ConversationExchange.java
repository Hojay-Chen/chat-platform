package com.luxera.companion.conversation;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** 一次自然互动(设计文档 §二十三): 用户连发多条+回复=一个 Exchange */
@Entity
@Table(name = "conversation_exchanges", indexes = @Index(name = "idx_exchange_session", columnList = "session_id"))
@Getter
@Setter
public class ConversationExchange {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** OPEN/CLOSED */
    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

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
