package com.luxera.companion.simulator.server;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * V10 §26/§29 simulator_devices 表: 一台绑定到普通聊天账号的"程序化客户端设备"。
 *
 * Chat Platform 只知道: 某用户账号下挂了一台设备, 设备有自己的凭据与权限。
 * 它不知道(也不允许知道)这台设备背后是数字人 —— V10 §30 无 BotUser。
 *
 * 状态机: PAIRING(已发起配对, 未激活) → ACTIVE(已连接过) → REVOKED(吊销)。
 *
 * <h2>两个后加的列: {@link #requestId} 与 {@link #companionId}</h2>
 *
 * 它们服务于同一件事 —— 聊天平台的「一键创建 Agent 好友」({@code AgentFriendProvisioningService})。
 * 那条链是**跨两个平台、三个写入点**的编排(本仓建账号 → 仓 2 建 agent → 本仓回写绑定),
 * 而它面对的调用方是一个会重试的界面。两个列各解决重试里的一半:
 *
 * <ul>
 *   <li>{@link #requestId} —— <b>幂等锚点</b>。它是调用方生成的, 不是本表的主键: 同一个
 *       requestId 重放要回到**同一台设备、同一个聊天账号**上, 而不是铸出第二份账号。
 *       没有它, "建了但响应丢了"的每一次重试都会多一个再也用不上的 SIMULATOR 账号。</li>
 *   <li>{@link #companionId} —— <b>绑定结果</b>。本表原先只记 accountId(本仓的账号),
 *       完全不知道对面那个 agent 是谁。记下之后, "这个 agent 说不了话"与"这个账号没有
 *       agent"才能被区分开 —— 对账({@code SimulatorDeviceReconciler})判的就是这一列。</li>
 * </ul>
 *
 * <p>两列都可空, 且不设 {@code nullable = false}: 它们是在有数据的表上加的,
 * {@code ddl-auto: update} 生成的 {@code add column ... not null} 在 PostgreSQL 上对
 * 已填充的表直接失败(与仓 2 {@code openapi_clients.can_act_for_users} 同一个坑)。
 */
@Entity
@Table(name = "simulator_devices", indexes = {
        @Index(name = "idx_simulator_devices_request", columnList = "request_id", unique = true),
})
@Getter
@Setter
public class SimulatorDevice {

    @Id
    @Column(name = "device_id", length = 64)
    private String deviceId;

    /** 拥有者: 聊天账号(user_id) —— simulator 登录的就是这个普通账号 */
    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    /** 伴侣名(仅用于设备管理界面展示, 不参与消息链路) */
    @Column(name = "display_name", length = 64)
    private String displayName;

    /**
     * 一键创建的**幂等锚点** —— 由调用方生成(同一先例: {@code messages.client_message_id})。
     *
     * <p>可空: 不是所有设备都从一键创建来(手工 provision / 二期以前的调用不带它),
     * 而唯一索引在 SQL 里对 NULL 不去重 —— 多行 NULL 并存正是想要的。
     */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /**
     * 对面那个 agent 的 id({@code companions.id}, 仓 2 的命名空间)——
     * <b>不是</b> {@link #accountId}, 两者按设计永不互换。
     *
     * <p>可空且长期可空: PAIRING 阶段 agent 还没建出来(第 3 步才建), 而第 3 步失败时
     * 它永远停在 null —— 那正是对账要找的形状。
     */
    @Column(name = "companion_id", length = 36)
    private String companionId;

    /** 配对码(6位, 10分钟有效; 展示给 DH 侧完成绑定) */
    @Column(name = "pairing_code", length = 8)
    private String pairingCode;

    @Column(name = "pairing_code_expires_at")
    private LocalDateTime pairingCodeExpiresAt;

    /** PAIRING | ACTIVE | REVOKED */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "PAIRING";

    /** 授权 scopes(逗号分隔): chat.read,chat.send,conversation.list,delivery.update */
    @Column(name = "scopes", nullable = false, length = 256)
    private String scopes;

    /** token_version: 每次吊销/轮换递增, 使已签发 JWT 全部失效 */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @Column(name = "secret_hash", length = 100)
    private String secretHash;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = "sim-dev-" + java.util.UUID.randomUUID().toString().substring(0, 12);
        }
    }
}
