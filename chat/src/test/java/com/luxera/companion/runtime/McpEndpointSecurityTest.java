package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code /mcp} 在<b>过滤器链</b>上的可达性。
 *
 * <p>这个类的存在理由是一次真实的失败: {@code SecurityConfig} 的
 * {@code anyRequest().authenticated()} 会把每一个 MCP 请求在过滤器上就变成 403 ——
 * 回的是 Spring 默认的 {@code {"timestamp":…,"status":403,"path":"/mcp"}}, 根本到不了
 * {@code McpController}。而 MCP 客户端<b>没有 JWT</b> 可给: 它是外部 Agent, 手里只有
 * {@code X-Mcp-Principal} + 服务密钥。于是"功能做完了"与"MCP 整条链路是死的"在
 * {@code application-platform} 的进程内测试里长得一模一样 —— 那边的测试应用没有
 * {@code SecurityConfig}(它在 platform-kernel), 过滤器链压根不在场。
 *
 * <p>所以这条断言只能落在这里: {@code bootstrap-app} 是唯一同时看得见
 * {@code SecurityConfig} 与 {@code McpController} 的地方。
 *
 * <p>两条断言分别对应两件事:
 * <ol>
 *   <li><b>放行</b> —— 带上正确的服务密钥, {@code initialize} 必须真的握手成功。
 *       把 {@code /mcp} 挪回 {@code anyRequest()} 后面, 这一条立刻变红。</li>
 *   <li><b>但不是敞开</b> —— 什么都不带时, 回的是 MCP 自己的形状(JSON-RPC 的 error 信封),
 *       而不是 Spring 那个默认错误体。这道区别就是"请求到了控制器、由控制器拒绝"与
 *       "请求被过滤器拦下、控制器从没听说过它"的分界。</li>
 * </ol>
 *
 * <p>{@code permitAll} 不等于无鉴权: 控制器第一步就是 {@code McpPrincipalResolver},
 * 服务密钥为空时它拒绝一切请求。这里配的是<b>测试专用</b>密钥(见 {@code application-test.yml}),
 * 生产上必须由 {@code LAP_MCP_SERVICE_KEY} 显式给。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.luxera.chatserver.ChatPlatformApplication.class)
@Import(McpEndpointSecurityTest.StubAgentPlatform.class)
@AutoConfigureMockMvc
class McpEndpointSecurityTest {

    private static final String MCP = "/mcp";
    /** 与 application-test.yml 里的 app.lap.mcp.service-key 一致。 */
    private static final String KEY = "test-mcp-service-key";


    /**
     * G1 拆分后 chat 的 classpath 上不再有仿真 Agent 平台 —— {@code CompanionDirectoryPort}
     * 的实现住在另一个仓库。本测试要验的是过滤器链与 MCP 适配器本身, 数字人那侧给一个
     * 静默桩即可（onUserMessage 什么都不做, 与"进程外的 DH 此刻不在"等价）。
     *
     * <p>G3 起进程内占位被 {@code HttpCompanionDirectoryAdapter} 取代（@Component 常驻,
     * 不再 @ConditionalOnMissingBean 退位）—— 测试上下文里真适配器与桩并存, 桩标
     * {@code @Primary} 盖掉它: 单测不该真的去连 8091。
     */
    @TestConfiguration
    static class StubAgentPlatform {
        @Bean
        @org.springframework.context.annotation.Primary
        CompanionDirectoryPort stubCompanionDirectory() {
            return new CompanionDirectoryPort() {
                @Override
                public CompanionRef requireOwned(String userId, String companionId) {
                    return new CompanionRef(companionId, "桩数字人", companionId);
                }

                @Override
                public void onUserMessage(String userId, String companionId, String conversationId,
                                          java.util.List<MessageView> messages) {
                    // 进程外: 聊天平台 fire-and-forget, 桩上什么都不用做
                }
            };
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void theFilterChainLetsAnAgentHandshakeWithoutAJwt() throws Exception {
        MvcResult result = mockMvc.perform(post(MCP)
                        .header("X-Mcp-Principal", "AGENT:security-probe")
                        .header("X-Mcp-Service-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "MCP 客户端没有 JWT —— 握手被过滤器拦下说明 /mcp 还压在 anyRequest().authenticated() 后面");
        assertNotNull(result.getResponse().getHeader("Mcp-Session-Id"),
                "initialize 应当开出一个协议会话");
        assertEquals("2025-06-18", json(result).path("result").path("protocolVersion").asText());
    }

    @Test
    void aRequestWithoutCredentialsIsRefusedByTheAdapterNotBySpring() throws Exception {
        MvcResult result = mockMvc.perform(post(MCP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andReturn();

        // 401: 凭据没给对。但真正被断言的是"这个 401 是谁发的" —— 形状必须是 JSON-RPC 信封。
        assertEquals(401, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertTrue(body.has("error"), "回的不是 JSON-RPC 错误信封: " + body);
        assertFalse(body.has("path"),
                "这是 Spring 默认错误体, 说明请求根本没到 McpController: " + body);
        assertEquals("UNIDENTIFIED_PRINCIPAL", body.path("error").path("data").path("code").asText());
    }

    private JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }
}
