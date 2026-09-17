package com.luxera.companion.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** {@link ChatApiClient} 的仓储。查找方式只有两种: 按哈希(鉴权)与按 id/agent(管理)。 */
public interface ChatApiClientRepository extends JpaRepository<ChatApiClient, String> {

    /**
     * 鉴权那一条路 —— 唯一在请求链上跑的查询。
     *
     * <p>状态带在条件里而不是查回来再判: 被吊销的钥匙与不存在的钥匙在调用方看来必须是
     * <b>同一件事</b>(都是 401), 分两步判迟早会有人在中间那条分支上加一句"这里顺手把它
     * 当有效处理"。
     */
    Optional<ChatApiClient> findByApiKeyHashAndStatus(String apiKeyHash, String status);

    List<ChatApiClient> findByStatusOrderByCreatedAtDesc(String status);

    List<ChatApiClient> findByAgentIdAndStatus(String agentId, String status);
}
