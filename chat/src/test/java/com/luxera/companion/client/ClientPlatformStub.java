package com.luxera.companion.client;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * 客户端面这一组测试用的<b>共享</b>桩: 仓 2 的 {@link CompanionDirectoryPort} 实现不在这个
 * classpath 上(它在另一个仓库), 而 {@code MessageCoreService.send} 第一步就要调它。
 *
 * <h2>为什么是一个顶层类, 而不是每个测试类里各写一个嵌套的 {@code @TestConfiguration}</h2>
 *
 * <p>Spring 的测试上下文缓存是以<b>合并后的配置</b>为键的, 而 {@code @Import} 进来的
 * {@code @TestConfiguration} 类名就在那个键里。三个客户端面测试各写一份内容相同但类名不同的
 * 桩, 得到的是三个不同的键 —— 也就是三份完整的 {@code ChatPlatformApplication} 上下文、
 * 三次 Hibernate 建表、三个 Hikari 连接池。测试跑得慢还是次要的, 主要的是它们会各自持有
 * 一份 {@code conversation_read_state} 的内存态缓存, 于是"免打扰到底有没有生效"这类断言的
 * 参照物在三个上下文里是不同的对象。
 *
 * <p>共用一个类, 三个类就命中同一个键({@code @ActiveProfiles} 与
 * {@code @SpringBootTest(classes=…)} 必须逐字相同, 见各测试类的注解)。
 *
 * <p>桩的行为与 {@code ExternalChatAccessTest.StubAgentPlatform} 逐字相同 —— 那边立的先例是
 * "要验的是本仓的这一侧, 对面给一个静默回声即可"。
 */
@TestConfiguration
public class ClientPlatformStub {

    @Bean
    @Primary
    CompanionDirectoryPort stubCompanionDirectory() {
        return new CompanionDirectoryPort() {
            @Override
            public CompanionRef requireOwned(String userId, String companionId) {
                return new CompanionRef(companionId, "桩数字人", companionId);
            }

            @Override
            public void onUserMessage(String userId, String companionId, String conversationId,
                                      List<MessageView> messages) {
                // 进程外: 聊天平台 fire-and-forget, 测试里没有对面可通知
            }
        };
    }
}
