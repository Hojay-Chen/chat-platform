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
 * LAP v2: <b>一张把别人请进这个会话的票</b> —— 邀请链接的持久形态。
 *
 * <p>它讲的是"加入一个已有的会话"(§58: 分享 Session), 与"打开一个应用"(§58: 分享 Application,
 * 走 {@code POST /applications/{id}/sessions} 开一局新的)是两件事。两者都叫分享, 但前者
 * 落在这里, 后者落在 {@link ApplicationSessionRecord}。
 *
 * <p><b>只存 {@code token_hash}, 不存明文。</b> §14 管这叫 Capability Token: 链接
 * {@code https://luxera.app/join/gm_8f3a...} 里那段随机串是持票人唯一的凭据, 拿到它就能进会话。
 * 于是它和密码同一处理 —— 库里留 SHA-256, 明文只在 {@code InvitationService.mint} 的返回里
 * 出现<em>一次</em>, 之后永远验不出来。这一条是 R10 的验收断言:"token 明文不进库"守的就是它。
 *
 * <p><b>{@code role} / {@code joinPolicy}</b> 是邀请铸造时冻住的两个快照: 持票人按这张票当时
 * 约定的角色进来, 不按它现在是不是主人。这挡的是"链接发出去之后主人改了口" —— 票是票,
 * 会在发出去那一刻成立。
 */
@Entity
@Table(name = "session_invitation")
@Getter
@Setter
public class SessionInvitationRecord {

    /** 铸造出来, 还一次都没被用过。 */
    public static final String STATUS_CREATED = "CREATED";
    /** 用掉了。 */
    public static final String STATUS_CONSUMED = "CONSUMED";
    /** 过期了(时间到了, 或 {@code maxUses} 用尽)。 */
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** 被撤回了。 */
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /** SHA-256(token 明文) 的十六进制 —— 64 个字符, 正好占满 char(64)。 */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** 谁铸造的这张票。描述性, 与参与者行上的 principal 值一致。 */
    @Column(name = "created_by_type", nullable = false, length = 16)
    private String createdByType;

    @Column(name = "created_by_id", nullable = false, length = 64)
    private String createdById;

    /** 持票人进会话时的角色。 */
    @Column(nullable = false, length = 16)
    private String role;

    /** 铸造时冻住的加入策略快照(见类注释)。 */
    @Column(name = "join_policy", length = 16)
    private String joinPolicy;

    /** 最多能用几次; null = 不限。 */
    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(nullable = false, length = 16)
    private String status = STATUS_CREATED;

    /** 可选目标: 非空表示"给某一个具体的 principal 的定向邀请"(如邀请某位数字人)。 */
    @Column(name = "target_type", length = 16)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public boolean usable() {
        return STATUS_CREATED.equals(status) && !expiredAtClock();
    }

    public boolean revoked() {
        return STATUS_REVOKED.equals(status);
    }

    /** 时间到了没有 —— 只按钟表判, 不动状态机。状态机的 {@code EXPIRE} 拿它当输入。 */
    public boolean expiredAtClock() {
        return expiresAt != null && !expiresAt.isAfter(LocalDateTime.now());
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = STATUS_CREATED;
    }
}