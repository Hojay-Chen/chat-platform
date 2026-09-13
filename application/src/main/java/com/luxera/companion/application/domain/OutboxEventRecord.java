package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * LAP v1 §Event: <b>INBOX 订阅的持久化收件条目</b> —— 也就是"事务性发件箱"里的那封信。
 *
 * <p><b>为什么要有这张表。</b> 在它之前, 一条事件只有一条出路: 动作提交之后, 在
 * {@code afterCommit} 里直接交给 sink。这条路有两个洞, 而两个都真实存在:
 * <ul>
 *   <li><b>进程死在那两个动作之间</b> —— 业务已经提交, 事件却从未投出, 而且没有任何痕迹
 *       说明它本该投出。恢复之后没有任何人会回来补这一封;</li>
 *   <li><b>投递失败就是永久失败</b> —— 直投路径上的异常只写一条 WARN, 没有重试, 没有终态。</li>
 * </ul>
 * 收件箱把这件事变成"先落一行, 再慢慢投": 行与业务写在<em>同一个事务</em>里, 所以业务提交
 * 就一定有这封信, 业务回滚就一定没有 —— 不存在中间态。
 *
 * <p><b>主键是确定性的 64 位 hash</b>({@code sha256(eventId@subscriptionId)}), 不是随机 UUID。
 * 于是"同一条事件对同一条订阅"只可能有一行: 应用因为重启重发了同一条
 * {@code idTemplate} 铸出的事件, 插入会被主键直接挡住, 而不是投递两次。
 * {@code triggersAgent} 的事件被强制要求确定性 id({@code idTemplate}), 这个表正是那个要求
 * 兑现的地方。
 */
@Entity
@Table(name = "lap_outbox",
        indexes = @Index(name = "idx_outbox_status_created", columnList = "status, created_at"))
@Getter
@Setter
public class OutboxEventRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    /** 试到上限仍然投不出去。它是终态, 也是"需要人来看了"的信号 —— 不是静默丢弃。 */
    public static final String STATUS_DEAD = "DEAD";

    /** sha256(eventId + "@" + subscriptionId) —— 见类注释。 */
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "event_id", nullable = false, length = 256)
    private String eventId;

    @Column(name = "application_id", length = 128)
    private String applicationId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "target_uri", length = 256)
    private String targetUri;

    @Column(name = "subscription_id", length = 36)
    private String subscriptionId;

    /** 收件人 —— 由订阅决定, 不由 AgentRouteResolver 决定: INBOX 是订阅者的声明, 与"谁是数字人"无关。 */
    @Column(name = "principal_type", length = 16)
    private String principalType;

    @Column(name = "principal_id", length = 64)
    private String principalId;

    /** 完整的事件信封 JSON(已带上路由信息)。 */
    @Column(columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 32)
    private String status = STATUS_PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    public boolean pending() {
        return STATUS_PENDING.equals(status);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
