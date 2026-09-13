package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LAP v1: 幂等的物理形态。
 *
 * <p>唯一键 {@code (principal_type, principal_id, session_id, idempotency_key)} —— 作用域里必须
 * 带 principal, 否则真人的 key 会和 Agent 的撞车。
 *
 * <p><b>{@code session_id} 是 LAP v2 加进来的, 而它的安全性完全依赖于一条别处的性质: 每个动作
 * 都必然有一个会话</b>({@code ActionGateway.resolveSession} 五档解析, 第 5 档兜底新建)。
 * 这一条不能想当然 —— PostgreSQL 的唯一索引里 NULL 互不相等, 所以<em>只要有一个动作能在没有
 * 会话的情况下走到这里, 那些行的这个唯一键就退化成"不约束"</em>, 而且是静默退化: 索引还在,
 * 名字还对, 只是不再拦任何东西。以后若有人放宽会话解析(比如给 {@code ensureSession} 加一条
 * "解析不出来就返回 null"), 必须同时把 {@code session_id} 从唯一键里拿掉, 否则得到的是一个
 * 看起来在保护幂等、实际谁也没保护的索引。
 *
 * <p>带 {@code session_id} 换来的是: 同一个人在两局棋里用同一个客户端生成的 key 不再互相顶掉。
 * 进程内调用尤其明显 —— 那里的 key 是从 {@code correlationId + target} 派生的, 换了一局
 * target 就变了, 但派生规则一旦改动, 没有这一列就会立刻踩到。
 *
 * <p>{@code request_hash} 防"同 key 不同载荷": 同一个 key 配上不同的 body 是客户端 bug,
 * 静默按第一次的结果返回会让那个 bug 极其难查, 所以直接 422。
 *
 * <p><b>禁止 check → insert → execute</b>(那是 TOCTOU)。正确次序是
 * "插入 IN_PROGRESS(唯一索引即锁, 插入失败即已存在)→ 提交 → 执行 → 回填终态"。
 * 崩溃恢复靠 {@code started_at} 超时 + CAS 抢占, 见 {@code ActionInvocationReaperJob}。
 */
@Entity
@Table(name = "action_invocation",
        uniqueConstraints = @UniqueConstraint(name = "uq_action_invocation_key",
                columnNames = {"principal_type", "principal_id", "session_id", "idempotency_key"}))
@Getter
@Setter
public class ActionInvocationRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "principal_type", nullable = false, length = 16)
    private String principalType;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    @Column(name = "action_id", nullable = false, length = 128)
    private String actionId;

    @Column(name = "application_id", nullable = false, length = 128)
    private String applicationId;

    /**
     * 这次动作落在哪一个会话上。<b>非空, 而且必须是非空。</b>
     *
     * <p>见类注释: 唯一键里带着它, 而 PostgreSQL 的唯一索引对 NULL 不设防 —— 一列可空就等于
     * 这个唯一键随时可能退化成"不约束"。所以这里不只是"通常有值", 而是数据库层面的 NOT NULL:
     * 任何一条没有会话的调用行都插不进来, 于是"唯一键还管不管用"不再是一个需要人去记得的问题。
     */
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 32)
    private String status = InvocationStatus.IN_PROGRESS.name();

    /** 终态时回填的完整 ActionResponse JSON —— 重放时逐字节返回它。 */
    @Column(name = "response_json", columnDefinition = "text")
    private String responseJson;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** 崩溃恢复的判据: {@code now - started_at > timeout} 才允许抢占。 */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 重试计数, 防无限重放。 */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    public InvocationStatus statusEnum() {
        return InvocationStatus.valueOf(status);
    }

    public boolean terminal() {
        return statusEnum().terminal();
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (startedAt == null) startedAt = LocalDateTime.now();
    }
}
