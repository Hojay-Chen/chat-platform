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
 * LAP v2: <b>谁在这个会话里</b> —— 取代 v1 的 {@code installation}, 成为权限模型的第一维。
 *
 * <p>v1 问的是"你装了没有", v2 问的是"<b>你在不在这局里</b>"。这不是换了个名字: 装是一次
 * 决定(长期, 针对软件), 参与是一次关系(有始有终, 针对这一个运行实例)。同一局棋里的两个人
 * 是两条参与者行, 而他们的权限也各自独立 —— 这正是"多个 principal 共用一个应用实例"能落地
 * 的地方。
 *
 * <p><b>真人、Agent、外部 Agent 都是同一张表里的行。</b>没有"Agent 参与者"这种概念, 也没有
 * {@code agent_participant} 这张表。唯一区分它们的是 {@code principal_type}, 而它只影响平台
 * 内部两件事: 事件路由找不找得到它(见 {@code AgentRouteResolver}), 以及数字人侧记不记得住
 * 它代表的用户。权限判定<em>完全不看</em>它。
 *
 * <p>唯一键 {@code (session_id, principal_type, principal_id)}: 同一个人在同一局里只有一行。
 * 离开再回来是把这一行改回 {@code ACTIVE}, 而不是再插一行 —— 否则"这局里有几个人"会随着
 * 进出次数漂移。
 *
 * <p>{@code companion_id}/{@code user_id} 是 v1 会话行上那两个字段的迁址: 它们描述的是
 * "这个参与者代表谁", 是参与者自己的属性, 不该长在会话上 —— 会话是多人共用的。
 */
@Entity
@Table(name = "application_session_participant",
        uniqueConstraints = @UniqueConstraint(name = "uq_session_participant",
                columnNames = {"session_id", "principal_type", "principal_id"}))
@Getter
@Setter
public class SessionParticipantRecord {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_LEFT = "LEFT";
    public static final String STATUS_REMOVED = "REMOVED";

    /** 开局的人。可以结束会话、改加入策略、移除别人。 */
    public static final String ROLE_OWNER = "OWNER";
    /** 普通参与者。能做事, 但管不了这个会话。 */
    public static final String ROLE_MEMBER = "MEMBER";
    /** 旁观: 能读, 不能写(默认 profile 只给 READ 级别)。 */
    public static final String ROLE_OBSERVER = "OBSERVER";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "principal_type", nullable = false, length = 16)
    private String principalType;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    @Column(nullable = false, length = 16)
    private String role = ROLE_MEMBER;

    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    /**
     * 加入时展开成 {@code session_permission} 行的那份默认权限的名字。
     *
     * <p>单独一列而不是直接用 {@code role}: 一个会话的主人可以把这个人的权限降到 OBSERVER,
     * 而<em>不</em>改变他在会话里的身份(还能说话、还能被 @) —— 身份与权限是两件事。
     * 默认值就是 {@code role}, 于是不关心这件事的调用方什么也不用做。
     */
    @Column(name = "permission_profile", length = 16)
    private String permissionProfile;

    /** 与 {@code principal_id} 同为 64 —— 值直接来自解析出来的 principal, 上限就是 64。 */
    @Column(name = "companion_id", length = 64)
    private String companionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(columnDefinition = "text")
    private String metadata;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean owner() {
        return ROLE_OWNER.equals(role);
    }

    /** 没显式设过 profile 就用身份本身 —— 于是"不关心权限细节"的加入路径不需要写一行额外代码。 */
    public String effectiveProfile() {
        return permissionProfile == null || permissionProfile.isBlank() ? role : permissionProfile;
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (joinedAt == null) joinedAt = LocalDateTime.now();
        if (role == null) role = ROLE_MEMBER;
        if (status == null) status = STATUS_ACTIVE;
    }
}
