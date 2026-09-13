package com.luxera.companion.simulator.server;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
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
 */
@Entity
@Table(name = "simulator_devices")
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
