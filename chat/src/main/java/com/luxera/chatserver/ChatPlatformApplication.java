package com.luxera.chatserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 聊天平台服务启动类 —— G1 拆分后仓 1 的唯一入口。
 *
 * <p>扫描三个平台的包: 聊天（conversation/event/simulator）、应用（application/LAP）、
 * 公共件（auth/config/outbox/common）。仿真 Agent 平台（digitalhuman 及全部 runtime 包）
 * 在另一个仓库（simulation-agent-platform），进程外经 HTTP/DHCP 与本服务通信。
 *
 * <p>与原 {@code bootstrap-app.CompanionApplication} 的差别只有扫描边界:
 * 从"五个模块都在 classpath 里, 默认全扫"收紧为"显式列出的包才进容器" ——
 * 服务边界从此由启动类显式表达, 而不是由"谁碰巧在依赖里"表达。
 *
 * <p>本类住在 {@code com.luxera.chatserver}（业务包之外, 让启动类本身不被业务组件扫描
 * 误抓）, 因此 JPA 的仓储/实体扫描必须显式指向业务包根 —— 自动配置默认从启动类所在包
 * 递归, 那里一个业务类都没有。显式声明后 JpaRepositoriesAutoConfiguration 会退位
 * （@ConditionalOnMissingBean）, 不存在双注册。
 */
@SpringBootApplication(scanBasePackages = {
        // 聊天平台
        "com.luxera.companion.conversation",
        "com.luxera.companion.event",
        "com.luxera.companion.simulator",
        // 跨服务集成占位（G3 换成 HTTP 适配器）
        "com.luxera.companion.integration",
        // 应用平台（LAP）
        "com.luxera.companion.application",
        // 公共件（auth/config/outbox/common 顶层散件如 HealthController 也在这棵树下）
        "com.luxera.companion",
})
@EnableJpaRepositories(basePackages = "com.luxera.companion")
@EntityScan(basePackages = "com.luxera.companion")
@EnableAsync
@EnableScheduling
public class ChatPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatPlatformApplication.class, args);
    }
}
