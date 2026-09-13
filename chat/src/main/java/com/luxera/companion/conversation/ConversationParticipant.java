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
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * §五十二~§五十三 ConversationParticipant: 会话参与者。
 *
 * 把 Conversation 从"companionId 一对一"升级为"多参与者":
 * 一对一 = Agent + User; 群聊 = Agent + User + Alice + Bob(数据模型天然成立, UI 暂不开放群聊)。
 * 未来 Agent 在群聊中对每个参与者持有独立 Relationship, 这里先铺好数据层。
 */
@Entity
@Table(name = "conversation_participants",
        uniqueConstraints = @UniqueConstraint(name = "uk_conv_participant", columnNames = {"conversation_id", "member_id"}),
        indexes = @Index(name = "idx_conv_participant_member", columnList = "member_id"))
@Getter
@Setter
public class ConversationParticipant {

    public static final String ROLE_AGENT = "agent";
    public static final String ROLE_USER = "user";
    public static final String ROLE_OTHER = "other";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    /**
     * Opaque participant id. USER → chat user id; AGENT → the peer id chat was handed by the
     * digital-human platform (today the companion id). Chat never resolves it further.
     */
    @Column(name = "member_id", nullable = false, length = 36)
    private String memberId;

    /** agent / user / other */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
