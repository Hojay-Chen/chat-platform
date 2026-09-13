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
 * LAP v1: <b>Resource —— 统一读模型。</b>应用的当前状态长什么样, 真人、Agent、MCP 客户端
 * 读到的都是这一行。
 *
 * <p>{@code state_version} 是乐观并发令牌, {@code ResourceStore} 一律走 CAS:
 * 两个 principal 同时抢同一步棋, 输的那个拿到干净的 {@code STATE_CONFLICT} 与当前版本,
 * 而不是悄悄丢掉一次落子。
 *
 * <p>{@code uri} 唯一 —— 资源身份就是它的 URI({@code game://session/123})。
 */
@Entity
@Table(name = "resource")
@Getter
@Setter
public class ResourceRecord {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 256)
    private String uri;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "application_id", nullable = false, length = 128)
    private String applicationId;

    /** 归属链的最后一环; 集合型资源(如 {@code reminder://pending})没有会话, 为 null。 */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "state_json", columnDefinition = "text")
    private String stateJson;

    /** 单调递增; 每次成功写入 +1。CAS 的比对对象。 */
    @Column(name = "state_version", nullable = false)
    private long stateVersion;

    @Column(nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
}
