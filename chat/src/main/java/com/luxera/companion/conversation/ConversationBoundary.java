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

/** 对话边界(设计文档 §二十四): SOFT_END/HARD_END/PAUSE/BUSY/SLEEP/DISTRACTED/RETURN_LATER */
@Entity
@Table(name = "conversation_boundaries", indexes = @Index(name = "idx_boundary_conv", columnList = "conversation_id"))
@Getter
@Setter
public class ConversationBoundary {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** SOFT_END/HARD_END/PAUSE/BUSY/SLEEP/DISTRACTED/RETURN_LATER */
    @Column(nullable = false, length = 24)
    private String type;

    @Column(length = 500)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

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
