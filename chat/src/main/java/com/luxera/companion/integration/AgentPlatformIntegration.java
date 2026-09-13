package com.luxera.companion.integration;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * G1→G3 过渡: 仿真 Agent 平台（simulation-agent-platform 仓库）尚未以独立进程运行时,
 * 聊天平台对 {@link CompanionDirectoryPort} 的依赖先落在这份占位实现上。
 *
 * <p>{@code onUserMessage} 的语义本来就是 fire-and-forget（V10 §2.1）: 聊天平台落完消息
 * 就走, 数字人收不到时由 outbox 兜底重投。所以占位实现 = "那边暂时没有进程在听",
 * 消息照常落库、SSE 照常推给前端 —— 聊天平台自身不因 Agent 平台缺席而失败。
 * 唯一会在占位上失败的调用是 {@code requireOwned}: 归属校验需要真的目录, 没有目录时
 * 诚实地说"查不到", 而不是一律放行(那会把别人的伴侣当成自己的)。
 *
 * <p>G3 落地后本类删除, 由 {@code HttpCompanionDirectoryAdapter} 取而代之
 * （调 agent-server 的 /internal 端点, 同一密钥体系）。留 {@code @ConditionalOnMissingBean}
 * 是为了将来切换时零改动: 新适配器注册后这份占位自动退位。
 */
@Configuration
public class AgentPlatformIntegration {

    @Bean
    @ConditionalOnMissingBean(CompanionDirectoryPort.class)
    CompanionDirectoryPort placeholderCompanionDirectory(
            @Value("${app.agent-platform.base-url:http://127.0.0.1:8091}") String agentPlatformBase) {
        return new CompanionDirectoryPort() {

            @Override
            public CompanionRef requireOwned(String userId, String companionId) {
                // Agent 平台不在: 查无此伴 —— 诚实回答, 不放行也不 500。
                throw new IllegalArgumentException("仿真 Agent 平台尚未接入, 查不到伴侣 " + companionId
                        + " 的归属（计划接入地址 " + agentPlatformBase + "）");
            }

            @Override
            public void onUserMessage(String userId, String companionId, String conversationId,
                                      List<MessageView> messages) {
                // fire-and-forget: 占位上等价于"对方进程不在场"。outbox 兜底链路仍在
                // （OutboxRelay 的 chat 侧入队不依赖本端口）, 消息不丢。
            }
        };
    }
}
