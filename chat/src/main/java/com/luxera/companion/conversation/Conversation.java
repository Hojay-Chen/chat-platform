package com.luxera.companion.conversation;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "conversations")
@Getter
@Setter
public class Conversation {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /**
     * 这个 Agent 在这段会话里**用哪个聊天账号说话** = 聊天平台的 {@code users.id}。
     *
     * <h2>为什么会话上要单独记一列, 而不是每次去查设备表</h2>
     *
     * 因为消息的 {@code sender_id} 与参与者的 {@code member_id} 都要用它, 而这两处是
     * **每一条消息落库时**都要判定的({@code ConversationReadStateService.bumpOnMessage}
     * 逐个参与者比 {@code member_id} 与 {@code sender_id} 来决定跳过谁)。让那条热路径
     * 每次都去查一次设备表, 是把一个一次性的迁移问题变成永久的运行时依赖。
     *
     * <h2>为什么可空, 以及为空时该怎么办</h2>
     *
     * 因为它是**后加的列**, 而且加它的时候平台上已有 44 段会话、其中 42 段的 Agent 还没有
     * 聊天账号(账号是这次才补铸的)。{@code ddl-auto: update} 加可空列没问题, 加非空列在
     * 有数据的表上直接失败。
     *
     * <p>为空**不是**"这段会话的 Agent 没有身份" —— 它是一个过渡态, 读的那一侧必须有回退:
     * 取不到这个值时就退回 {@link #companionId}(见
     * {@code ConversationService#deriveSenderId} 与 {@code CompanionDirectoryPort} 那边
     * {@code peerMemberId} 的同一条回退规则)。有回退, 这次迁移才能分步做, 不必一次性切换。
     *
     * <p>与 {@link #companionId} 的关系是"同一个 Agent 的两个 id", 永不互换:
     * 前者是 agent 平台标识 agent 个体的值, 这一个是聊天平台标识聊天账号的值。
     */
    @Column(name = "agent_account_id", length = 36)
    private String agentAccountId;

    @Column(nullable = false, length = 128)
    private String title = "我们的对话";

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @Column(length = 1000)
    private String summary;

    @Column(length = 32)
    private String status = "active";

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
