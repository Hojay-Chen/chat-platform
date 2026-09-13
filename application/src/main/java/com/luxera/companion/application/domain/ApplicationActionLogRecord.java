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
 * LAP v1: 动作审计日志。<b>取代 {@code dh_application_action_log}。</b>
 *
 * <p>旧表有两个问题: 一, 它没有 invocation 关联, 无法把"这一次审计"与"那一次幂等调用"
 * 对上; 二, {@code permission_decision} 与 {@code execution_status} 用同一个参数赋值,
 * 于是前者是个废字段 —— 而"权限为什么被拒"恰恰是审计最该回答的问题。这里两者分开,
 * 且都是真的。
 */
@Entity
@Table(name = "application_action_log")
@Getter
@Setter
public class ApplicationActionLogRecord {

    @Id
    @Column(length = 36)
    private String id;

    /** 关联的幂等调用; READ 动作没有 invocation, 为 null。 */
    @Column(name = "invocation_id", length = 36)
    private String invocationId;

    @Column(name = "action_id", nullable = false, length = 128)
    private String actionId;

    @Column(name = "application_id", length = 128)
    private String applicationId;

    @Column(name = "principal_type", length = 16)
    private String principalType;

    @Column(name = "principal_id", length = 64)
    private String principalId;

    /** 与 {@code principal_id} 同宽: 见 {@code ApplicationSessionRecord} 里的同名说明。 */
    @Column(name = "companion_id", length = 64)
    private String companionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "resource_uri", length = 256)
    private String resourceUri;

    /** ALLOW / DENY / REQUIRE_CONFIRMATION —— 与 execution_status 分开, 不再是废字段。 */
    @Column(name = "permission_decision", length = 32)
    private String permissionDecision;

    @Column(name = "execution_status", length = 32)
    private String executionStatus;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
