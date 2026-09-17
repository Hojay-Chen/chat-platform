package com.luxera.companion.access;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 一把发给**平台外程序**的聊天接入钥匙 —— 以及这把钥匙被授权代表哪个 Agent 说话。
 *
 * <h2>为什么必须新开一张表, 而不是复用 LAP 的开发者/client 概念</h2>
 *
 * 因为租户边界的答案不一样。LAP 的开发者身份表达的是"这个程序能上架/能调哪些应用",
 * 而 {@code /mcp} 的身份({@code X-Mcp-Principal: AGENT:xxx})是<b>自报</b>的 ——
 * 任何持服务密钥的人都能声称自己是任何一个 agent。作为"谁在上架应用"够用, 作为
 * "谁在读某个 Agent 的私信"完全不够: 那不是租户边界, 是一句声明。
 *
 * <p>本表把归属变成一行数据: {@code agent_id} 是本表的一列, 由持 admin key 的平台
 * 管理员写入, 调用方无法自报。{@code ApiKeyPrincipalResolver} 解出来的
 * {@code companionId} 从这里来, 于是"这个钥匙只能碰这个 Agent 的会话"成为一条
 * 可以判定的性质, 而不是一条约定。
 *
 * <p>表名刻意叫 {@code chat_api_clients} 而不是 {@code openapi_clients} ——
 * 后者在仿真 Agent 平台(仓 2)已经是一张语义不同的表(那侧管的是"哪个程序能建 agent"),
 * 两张表同名会让人以为它们是同一份数据的两个视图。
 *
 * <h2>为什么存 sha256 而不是 bcrypt</h2>
 *
 * 因为鉴权是"按哈希反查一行"。bcrypt 每行盐不同, 没法做索引查找 —— 要校验就得全表扫、
 * 逐行 verify。而这把钥匙是 32 字节的服务端随机串, 不存在字典可查、也没有"弱密码"这回事,
 * 慢哈希在这里防的是不存在的攻击, 代价是每个请求全表扫。sha256 在这里的作用只有一个:
 * 库被读走时, 里面躺着的不是能直接用的钥匙。
 */
@Getter
@Setter
@Entity
@Table(name = "chat_api_clients")
public class ChatApiClient {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    /** 客户端 id —— 审计与吊销用, 与聊天账号 id / agent id 都不是一个命名空间。 */
    @Id
    @Column(name = "client_id", length = 36, nullable = false)
    private String clientId;

    /** 给人看的名字("小满的桌面端"), 不参与鉴权。 */
    @Column(name = "name", length = 128)
    private String name;

    /** sha256(明文钥匙) 的十六进制 —— 明文只在签发那一次出现。 */
    @Column(name = "api_key_hash", length = 64, nullable = false, unique = true)
    private String apiKeyHash;

    /** 明文的前几位, 让管理员在列表里认出"这是哪一把", 不足以重建明文。 */
    @Column(name = "api_key_prefix", length = 16)
    private String apiKeyPrefix;

    /**
     * 这把钥匙代表哪个 Agent —— <b>本表就是租户边界的定义</b>。
     *
     * <p>是 Agent 平台的 agent id({@code companions.id}), 不是聊天账号 id: 发钥匙的
     * 对象是"某个 Agent 的驱动程序", 它自己再去拿聊天账号(经 {@code AgentChatIdentity})。
     * 两者按设计永不可互换, 见 {@code Companion#chatAccountId} 的说明。
     */
    @Column(name = "agent_id", length = 36, nullable = false)
    private String agentId;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 吊销时间。保留行而不是删除: 一把被吊销的钥匙再被使用时, 日志里要能答出"它何时被吊销的"。 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
