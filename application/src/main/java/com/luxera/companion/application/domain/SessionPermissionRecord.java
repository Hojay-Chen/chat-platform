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
 * LAP v2: 一条参与者在<b>这一个会话里</b>的授权 —— {@code permission_grant} 的继任者。
 *
 * <p>与 v1 那张表的唯一区别是本列: 外键从 {@code installation_id} 换成了
 * {@code participant_id}。这个替换就是整个权限模型搬迁的全部内容 ——
 * "装了什么"变成"在这场里担任什么", 授权的作用域随之从"这个应用"收窄到"这一局"。
 * 收窄是有意的: 在一局棋里授予的写权限不该自动延续到下一局。
 *
 * <p>{@code capability_id} 与 {@code action_id} 至少一个非空: 授权可以是"这个能力都能用",
 * 也可以是"只准用这一个动作"。两个都空意味着一条什么都不允许的授权, 那是数据错误。
 *
 * <p><b>这张表是"角色 → 默认 profile"展开的结果, 不是调用方逐条写进来的。</b>
 * 加入会话时 {@code ParticipantService} 按 profile 铺一批行下来; 想让某个人多一条或少一条,
 * 在那之后改这一张表即可 —— 与 v1 的 {@code grantMissing} 一样, 展开是"只补不覆盖"。
 */
@Entity
@Table(name = "session_permission")
@Getter
@Setter
public class SessionPermissionRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "participant_id", nullable = false, length = 36)
    private String participantId;

    @Column(name = "capability_id", length = 64)
    private String capabilityId;

    @Column(name = "action_id", length = 128)
    private String actionId;

    @Column(name = "permission_level", nullable = false, length = 16)
    private String permissionLevel;

    /** 允许的最高风险; 动作的风险高于它即拒绝。 */
    @Column(name = "risk_ceiling", nullable = false, length = 16)
    private String riskCeiling;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public boolean expired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    /** 覆盖某个动作: 精确命中 action_id, 或命中它所属的能力。 */
    public boolean covers(String candidateActionId, String candidateCapabilityId) {
        if (actionId != null) return actionId.equals(candidateActionId);
        return capabilityId != null && capabilityId.equals(candidateCapabilityId);
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
