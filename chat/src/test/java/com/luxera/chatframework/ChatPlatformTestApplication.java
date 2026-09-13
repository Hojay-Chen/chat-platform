package com.luxera.chatframework;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ApplicationCatalogPort;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * Test-only bootstrap for the chat module.
 *
 * <p>G1 拆分后这里多了一层约束: chat 的测试 classpath 上**真的**带着 application(LAP) 模块,
 * 但这组测试要守的性质恰恰是"聊天平台不认识任何具体应用" —— {@link ChatTestApplicationCatalog}
 * 注册的是一个本仓库从未见过的应用(com.example.paper-plane)。所以本启动类显式把扫描
 * 收紧到聊天三包, 真正的 LAP bean 一概不进容器: {@link ApplicationCatalogPort} 在这些
 * 测试里只有 stub 一个实现, 而每一条断言都落在一个陌生应用上。
 *
 * <p>本类住在 {@code com.luxera.chatframework} 而不是业务包 com.luxera.companion 下:
 * 主启动类的组件扫描以包前缀递归, 一个带 {@code @SpringBootApplication} 的类放在
 * 根下会被使用主启动类的测试一并扫进来 —— 于是"真 LAP"与"stub"两个 catalog 同时在场。
 *
 * <p>需要真 LAP 的端到端测试走主启动类 {@code ChatPlatformApplication}
 * (见 {@code McpEndpointSecurityTest} —— 它验的是过滤器与真适配器)。
 */
@SpringBootApplication(scanBasePackages = {
        "com.luxera.companion.conversation",
        "com.luxera.companion.event",
        "com.luxera.companion.simulator",
        "com.luxera.companion.auth",
        "com.luxera.companion.config",
        "com.luxera.companion.outbox",
        "com.luxera.companion.common",
})
@EnableJpaRepositories(basePackages = {
        "com.luxera.companion.conversation",
        "com.luxera.companion.event",
        "com.luxera.companion.simulator",
        "com.luxera.companion.auth",
        "com.luxera.companion.outbox",
        "com.luxera.companion.common",
})
@EntityScan(basePackages = {
        "com.luxera.companion.conversation",
        "com.luxera.companion.event",
        "com.luxera.companion.simulator",
        "com.luxera.companion.auth",
        "com.luxera.companion.outbox",
        "com.luxera.companion.common",
})
@EnableAsync
@EnableScheduling
public class ChatPlatformTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatPlatformTestApplication.class, args);
    }

    /** LAP v2 §64: 聊天平台从不实现 {@link ApplicationCatalogPort} —— 这里给的是刻意陌生的假应用。 */
    @Bean
    ApplicationCatalogPort testApplicationCatalog() {
        return new ChatTestApplicationCatalog();
    }

    /** 聊天平台从不实现 {@link CompanionDirectoryPort} —— 那是仿真 Agent 平台的活, 测试里给回声桩。 */
    @Bean
    CompanionDirectoryPort testCompanionDirectory() {
        return new CompanionDirectoryPort() {
            @Override
            public CompanionRef requireOwned(String userId, String companionId) {
                return new CompanionRef(companionId, "测试伴侣", companionId);
            }

            @Override
            public void onUserMessage(String userId, String companionId, String conversationId,
                                      List<MessageView> messages) {
                // 模块测试里没有 Agent 平台: 消息落库即结束
            }
        };
    }
}
