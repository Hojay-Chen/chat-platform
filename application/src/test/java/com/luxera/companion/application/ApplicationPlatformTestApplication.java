package com.luxera.companion.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test-only bootstrap for the application platform.
 *
 * <p>In production this module is launched by {@code bootstrap-app} alongside the chat platform and
 * the digital human. Here it runs alone, which is the property worth testing: the platform can
 * discover, read and execute applications with neither of the other two present.
 *
 * <p>The two ports it cannot answer itself are faked: chat is where the SSE side of a game reaches
 * the browser, and the event sink is the digital human's door, which belongs to
 * {@code digital-human-platform} and is therefore absent by construction.
 *
 * <p><b>扫描范围要显式写出两个包。</b>{@code com.luxera.companion.access} 不是
 * {@code com.luxera.companion.application} 的子包(是兄弟), 所以默认的"从启动类所在包递归"
 * 扫不到它 —— 而 {@code ApiKeyPrincipalResolver}(在 {@code application.principal} 下, 扫得到)
 * 依赖那里的 {@code ChatApiClientRepository}。结果是整个上下文起不来, 十几条用例一起变红,
 * 报的却是 {@code ActionGatewayTest} 里一句 {@code Failed to load ApplicationContext}。
 * 那个包属于本模块(接入面的实体与仓储都在 {@code application} 里, 因为解析器需要它们),
 * 所以列在这里是对的, 不是把外部的什么东西塞进测试。
 */
@SpringBootApplication(scanBasePackages = {
        "com.luxera.companion.application",
        "com.luxera.companion.access",
})
@EnableJpaRepositories(basePackages = {
        "com.luxera.companion.application",
        "com.luxera.companion.access",
})
@EntityScan(basePackages = {
        "com.luxera.companion.application",
        "com.luxera.companion.access",
})
public class ApplicationPlatformTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationPlatformTestApplication.class, args);
    }

    /** 返回具体类型(而非端口类型), 测试才能直接注入并断言录到了什么。 */
    @Bean
    RecordingChatWorld recordingChatWorld() {
        return new RecordingChatWorld();
    }

    @Bean
    RecordingApplicationEventSink recordingEventSink() {
        return new RecordingApplicationEventSink();
    }

    /**
     * 令牌读取在 kernel, 这里没有 kernel, 于是塞一个能用的替身 —— 见
     * {@link StubPrincipalTokenReader} 的类注释: 替身能造出两种身份, 跨 principal 的用例才测得到。
     */
    @Bean
    StubPrincipalTokenReader stubPrincipalTokenReader() {
        return new StubPrincipalTokenReader();
    }
}
