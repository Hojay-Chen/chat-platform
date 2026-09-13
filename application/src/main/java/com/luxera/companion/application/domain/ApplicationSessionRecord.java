package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LAP v2: <b>一次"打开这个应用"的运行实例</b> —— 用户无需安装, 只需要一个会话。
 *
 * <p>它是资源归属的锚: 棋局(应用自己的业务对象)挂在它下面, {@code resource.session_id}
 * 指向它。<b>但它不再是"某个 principal 的实例"</b> —— v1 的
 * {@code Application → Installation → ApplicationSession → Resource} 那条链里, 会话是
 * 某个人的安装的下游; v2 的会话是<em>多人共用的一个世界</em>, 谁在里面由
 * {@link SessionParticipantRecord} 说了算。
 *
 * <p>于是 v1 的 {@code principal_type}/{@code principal_id}/{@code companion_id}/{@code user_id}
 * 四个字段整体消失, 换成一对 {@code owner_principal_*} —— 那是"谁开的这个会话", 不是"这个会话
 * 属于谁"。这个区别是整次重构的核心: 一盘棋的开局人只有一个, 下棋的人可以有两个。
 *
 * <p><b>状态机是五态</b>({@link #STATUS_CREATED} / {@link #STATUS_WAITING} /
 * {@link #STATUS_ACTIVE} / {@link #STATUS_PAUSED} / {@link #STATUS_ENDED}), 由
 * {@code ApplicationSessionStateMachine} 强制。<b>刻意不叫"结束"以外的任何东西为终态</b>:
 * 只有 {@code ENDED} 不可逆, 其余四个状态之间都能来回走 —— "等人" 与 "暂停" 都是临时的。
 *
 * <p>注意这与 {@code ApplicationStatus} 的十态是两回事, 不要合并: 那十个说的是
 * <b>软件</b>在生命周期里的位置(草稿/审核/上架/下架), 这五个说的是
 * <b>一个运行实例</b>在生命周期里的位置。同一个已上架应用可以同时有几百个 ACTIVE 会话。
 */
@Entity
@Table(name = "application_session")
@Getter
@Setter
public class ApplicationSessionRecord {

    /** 已创建, 还没凑够起步人数。 */
    public static final String STATUS_CREATED = "CREATED";
    /** 在等人 —— 只挡写动作, 读照常(等人时看一眼棋盘是合理的)。 */
    public static final String STATUS_WAITING = "WAITING";
    /** 正常进行中 —— 唯一允许写动作的状态。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 暂停 —— 与 WAITING 一样只挡写。 */
    public static final String STATUS_PAUSED = "PAUSED";
    /** 终态。 */
    public static final String STATUS_ENDED = "ENDED";

    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_UNLISTED = "UNLISTED";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";

    public static final String JOIN_OPEN = "OPEN";
    public static final String JOIN_INVITE_ONLY = "INVITE_ONLY";
    public static final String JOIN_CLOSED = "CLOSED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "application_id", nullable = false, length = 128)
    private String applicationId;

    /** 会话创建时钉住的版本 —— 与 v1 的安装一样, 升级是显式动作。 */
    @Column(name = "version_id", nullable = false, length = 36)
    private String versionId;

    /** 谁开的这个会话。<b>不等于"这个会话属于谁"</b> —— 参与者可以有很多个。 */
    @Column(name = "owner_principal_type", nullable = false, length = 16)
    private String ownerPrincipalType;

    @Column(name = "owner_principal_id", nullable = false, length = 64)
    private String ownerPrincipalId;

    @Column(nullable = false, length = 32)
    private String status = STATUS_CREATED;

    @Column(nullable = false, length = 16)
    private String visibility = VISIBILITY_UNLISTED;

    @Column(name = "join_policy", nullable = false, length = 16)
    private String joinPolicy = JOIN_INVITE_ONLY;

    /**
     * 凑够多少人会话才转 ACTIVE。<b>默认 1</b> —— 这个默认值是有分量的: 它保证"打开即用"
     * 的一切既有流程语义不变, 想让某个应用变成"够人才开始"的, 显式把它设成 2。
     */
    @Column(name = "min_participants", nullable = false)
    private int minParticipants = 1;

    @Column(name = "max_participants", nullable = false)
    private int maxParticipants = 8;

    /** 这个会话被带进了哪一段对话(§85)。为空的会话是"从应用市场直接打开"的。 */
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    /** 应用自定义的会话级元数据(JSON 文本)。平台不解释它, 只替它保管。 */
    @Column(columnDefinition = "text")
    private String metadata;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 终态。只有它不可逆, 也只有它让会话彻底不可用(连读都不行)。 */
    public boolean ended() {
        return STATUS_ENDED.equals(status);
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastActiveAt == null) lastActiveAt = createdAt;
    }
}
