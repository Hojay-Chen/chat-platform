package com.luxera.companion.conversation;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 「**我**把这个会话读到哪了」—— 一件属于「会话 × 人」的事, 不是属于会话的事。
 *
 * <h2>为什么没有挂在 {@link Conversation} 上</h2>
 *
 * 一张 {@code conversations} 行要服务会话里的每一个人。可「未读几条」「置顶了没有」
 * 「免打扰到什么时候」这三件事**每个人都不一样** —— 挂在会话上, 群聊一开就必须
 * 拆表 + 迁数据 + 改所有查询。今天的未读之所以看着"能挂在会话上", 只是因为
 * 一对一里会话恰好只有一个人。
 *
 * <p>所以一期就落到 {@code (conversation_id, member_id)} 这个维度:
 * {@code UNIQUE(conversation_id, member_id)} 一行一个人, 群聊直接复用同一张表, 零迁移。
 * 一期的 {@code memberId} 恒为当前用户的 id。
 *
 * <h2>为什么把 unreadCount 存成计数而不是每次数一遍</h2>
 *
 * 计数要在写消息时维护, 数一遍要在列表里对每个会话各查一次 —— 后者正是这个列表页
 * 最容易变成 N+1 的地方。而"发出消息时给除发送者外的每个成员 +1"这件事在群聊里
 * 同样是正确的, 不需要换算法。
 */
@Entity
@Table(name = "conversation_read_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_conv_read_member",
                columnNames = {"conversation_id", "member_id"}))
@Getter
@Setter
public class ConversationReadState {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    /** 与会话参与者同一个语义的不透明成员 id —— 一期恒为当前用户 id */
    @Column(name = "member_id", nullable = false, length = 36)
    private String memberId;

    /** 最后一次已读消息。比时间戳可靠: 同一毫秒里的两条消息不会因此互相盖掉 */
    @Column(name = "last_read_message_id", length = 36)
    private String lastReadMessageId;

    @Column(name = "unread_count", nullable = false)
    private int unreadCount = 0;

    /** 置顶时间。为空 = 未置顶 —— 用时间而不是布尔, 是为了"最近置顶的排前面" */
    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    /** 免打扰截止时间。为空或已过期 = 正常提醒 */
    @Column(name = "muted_until")
    private LocalDateTime mutedUntil;

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
