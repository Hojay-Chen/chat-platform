package com.luxera.companion.conversation;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * §30 Conversation Thread: 一段围绕某个话题的连续对话。
 * 真人经常不是"说完全部内容后结束", 而是聊到一半去做别的事情, 过一段时间回来继续。
 * Thread 状态机: ACTIVE → PAUSED → RESUMABLE → ENDED / ABANDONED。
 */
@Entity
@Table(name = "conversation_threads", indexes = {
        @Index(name = "idx_thread_conversation", columnList = "conversation_id"),
        @Index(name = "idx_thread_companion", columnList = "companion_id")
})
@Getter
@Setter
public class ConversationThread {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** 话题摘要(例如 "电影", "工作", "被忽视的感觉") */
    @Column(nullable = false, length = 200)
    private String topic;

    /** 话题重要性 0-1 */
    @Column(nullable = false)
    private double importance = 0.5;

    /** 当前话题的情感基调(例如 轻松/紧张/温暖) */
    @Column(name = "emotional_tone", length = 32)
    private String emotionalTone;

    /** 未解决的意图(例如 "想解释为什么没及时回复") */
    @Column(name = "unresolved_intent", length = 300)
    private String unresolvedIntent;

    /** 话题状态: ACTIVE / PAUSED / RESUMABLE / ENDED / ABANDONED */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
