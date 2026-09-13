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

/** 一次连续聊天(设计文档 §二十二): 09:00-09:30 是一个 Session, 与 Conversation(长期空间)区分 */
@Entity
@Table(name = "interaction_sessions", indexes = @Index(name = "idx_session_conv", columnList = "conversation_id"))
@Getter
@Setter
public class ConversationSession {

    @Id
    @Column(name = "id", length = 36)
    private String id;

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
