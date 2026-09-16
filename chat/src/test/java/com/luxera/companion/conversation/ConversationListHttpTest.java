package com.luxera.companion.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.chatframework.ChatPlatformTestApplication;
import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/conversations} 在<b>真实过滤器链</b>上的可达性。
 *
 * <p>与 {@link ConversationListTest} 分工明确, 两条都不能省: 那边走 service, 验的是
 * "数据算得对不对"; 这边走 MockMvc, 验的是"请求能不能活着走到 controller"。历史教训就在
 * 隔壁的 {@code SecurityConfig} 注释里 —— R14 的开发者端点"控制器写好了、单元测试也绿
 * (它直接调 service, 不经过过滤器), 而真实调用连门都进不来"。**只测 service 的端点
 * 是没测过的端点。**
 *
 * <h2>这个测试为什么必须真造一行 User</h2>
 *
 * {@code /api/conversations} 落在 {@code anyRequest().authenticated()} 后面, 所以它要 JWT。
 * 而 {@code JwtAuthenticationFilter} 的放行条件是:
 *
 * <pre>jwtUtil.isValid(token) &amp;&amp; userRepository.existsById(jwtUtil.getUserId(token))</pre>
 *
 * —— <b>签名对还不够, 这个用户必须真的在库里</b>。所以这里不能伪造一个 principal 了事
 * (何况 {@code spring-security-test} 不是本仓依赖, 没有 {@code @WithMockUser} 可用),
 * 只能真落一行 User 再用 {@code JwtUtil} 签一个。这反过来也钉住了一条安全性质:
 * <b>销号即刻生效</b> —— 用户行没了, 手里那张还没过期的 JWT 立刻作废。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
@AutoConfigureMockMvc
class ConversationListHttpTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ConversationService conversationService;

    /** JUnit5 默认每个测试方法一个新实例, 所以这个 id 是每个方法独有的。 */
    private final String userId = UUID.randomUUID().toString();
    private final String companionId = UUID.randomUUID().toString();

    private String token;
    private String conversationId;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setId(userId);
        u.setUsername("http" + UUID.randomUUID().toString().replace("-", ""));
        u.setPasswordHash("not-a-real-hash");
        userRepository.save(u);

        token = jwtUtil.generateToken(userId, "http-test");
        conversationId = conversationService.create(userId, companionId, "测试会话", "林夏").getId();
    }

    // ── 过滤器 ─────────────────────────────────────────────────────

    @Test
    void withoutATokenTheRequestNeverReachesTheController() throws Exception {
        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aGarbageTokenIsRefusedToo() throws Exception {
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isForbidden());
    }

    /**
     * 签名合法但用户不存在 —— 过滤器那一半 {@code existsById} 的断言。
     *
     * <p>这条不是凑数: 少了它, 一个只验签名不查库的实现也能让上面两条通过, 而那种实现
     * 意味着**注销掉的账号手里那张 JWT 还能用到过期为止**。
     */
    @Test
    void aValidTokenForADeletedUserIsRefused() throws Exception {
        String ghost = jwtUtil.generateToken(UUID.randomUUID().toString(), "ghost");
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + ghost))
                .andExpect(status().isForbidden());
    }

    // ── 列表 ───────────────────────────────────────────────────────

    @Test
    void listReturnsMyConversationWithItsPeerAndPreview() throws Exception {
        conversationService.addMessage(conversationId, "user", userId, "在吗",
                null, null, null, false, null, null, null, null);

        JsonNode row = onlyRowOfMyList();
        assertEquals(conversationId, row.get("id").asText());
        assertEquals(companionId, row.get("peerId").asText());
        assertEquals("林夏", row.get("peerName").asText(), "对方名字取自参与者行");
        assertEquals("在吗", row.get("lastMessage").get("content").asText());
        assertEquals(userId, row.get("lastMessage").get("senderId").asText());
        assertFalse(row.get("pinned").asBoolean());
        assertFalse(row.get("muted").asBoolean());
    }

    @Test
    void listDoesNotLeakOtherPeoplesConversations() throws Exception {
        String stranger = UUID.randomUUID().toString();
        String theirs = conversationService
                .create(stranger, UUID.randomUUID().toString(), "别人的", "别人").getId();

        for (JsonNode row : listJson()) {
            assertFalse(theirs.equals(row.get("id").asText()),
                    "别人的会话不该出现在我的列表里");
        }
    }

    // ── 单条 ───────────────────────────────────────────────────────

    @Test
    void someoneElsesConversationIsRefused() throws Exception {
        String stranger = UUID.randomUUID().toString();
        String theirs = conversationService
                .create(stranger, UUID.randomUUID().toString(), "别人的", "别人").getId();

        // 400 是 requireVisible 里 BusinessException.badRequest 的现行映射。语义上 404
        // 更好(不泄露"这个 id 存在"), 但那是另一件事 —— 这里钉住现状, 免得它悄悄变了。
        mockMvc.perform(get("/api/conversations/" + theirs).header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownConversationIsNotFound() throws Exception {
        mockMvc.perform(get("/api/conversations/" + UUID.randomUUID()).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void messagesCarrySenderTypeAndSenderId() throws Exception {
        conversationService.addMessage(conversationId, "user", userId, "在吗",
                null, null, null, false, null, null, null, null);
        conversationService.addMessage(conversationId, "companion", "在的",
                null, null, null, false, null, null, null, null);

        JsonNode msgs = readJson(mockMvc.perform(
                        get("/api/conversations/" + conversationId + "/messages").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertEquals(2, msgs.size());
        assertEquals("user", msgs.get(0).get("senderType").asText());
        assertEquals(userId, msgs.get(0).get("senderId").asText());
        assertEquals("companion", msgs.get(1).get("senderType").asText());
        assertEquals(companionId, msgs.get(1).get("senderId").asText(),
                "companion 的 senderId 由会话推导 —— 数字人平台 append 时不知道账号 id");
    }

    @Test
    void someoneElsesMessagesAreRefused() throws Exception {
        String stranger = UUID.randomUUID().toString();
        String theirs = conversationService
                .create(stranger, UUID.randomUUID().toString(), "别人的", "别人").getId();

        mockMvc.perform(get("/api/conversations/" + theirs + "/messages").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    // ── 读 / 置顶 / 免打扰 ─────────────────────────────────────────

    @Test
    void markReadClearsTheUnreadBadge() throws Exception {
        conversationService.addMessage(conversationId, "companion", "在的",
                null, null, null, false, null, null, null, null);
        assertEquals(1, onlyRowOfMyList().get("unreadCount").asInt(), "先确认角标真的亮着");

        String body = "{\"lastMessageId\":null}";
        mockMvc.perform(post("/api/conversations/" + conversationId + "/read")
                        .header("Authorization", bearer())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        assertEquals(0, onlyRowOfMyList().get("unreadCount").asInt());
    }

    /**
     * 空 body 也要能读 —— {@code @RequestBody(required = false)} 存在的理由。
     *
     * <p>前端在"进入聊天室"和"窗口重新获得焦点"两处都会调它, 而那两处**手里没有**
     * 最后一条消息的 id(焦点那一处尤其: 用户只是切了下窗口)。要求它先查一次最后一条消息
     * 再发这个请求, 是把一件服务端一句话能表达的事推给客户端。
     */
    @Test
    void markReadWorksWithoutABodyAtAll() throws Exception {
        conversationService.addMessage(conversationId, "companion", "在的",
                null, null, null, false, null, null, null, null);

        mockMvc.perform(post("/api/conversations/" + conversationId + "/read")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());

        assertEquals(0, onlyRowOfMyList().get("unreadCount").asInt());
    }

    @Test
    void pinAndMuteRoundTripThroughTheApi() throws Exception {
        mockMvc.perform(post("/api/conversations/" + conversationId + "/pin")
                        .header("Authorization", bearer())
                        .contentType("application/json").content("{\"pinned\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/conversations/" + conversationId + "/mute")
                        .header("Authorization", bearer())
                        .contentType("application/json").content("{\"muted\":true}"))
                .andExpect(status().isOk());

        JsonNode row = onlyRowOfMyList();
        assertTrue(row.get("pinned").asBoolean());
        assertTrue(row.get("muted").asBoolean());
    }

    /** 单独 GET 一条 —— 前端从通知点进来时只需要这一行, 不该逼它拉整个列表。 */
    @Test
    void oneConversationCanBeFetchedOnItsOwn() throws Exception {
        JsonNode row = readJson(mockMvc.perform(
                        get("/api/conversations/" + conversationId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertEquals(conversationId, row.get("id").asText());
        assertEquals("林夏", row.get("peerName").asText());
    }

    // ── 工具 ───────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }

    private JsonNode listJson() throws Exception {
        String body = mockMvc.perform(get("/api/conversations").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return readJson(body);
    }

    private JsonNode onlyRowOfMyList() throws Exception {
        JsonNode list = listJson();
        assertEquals(1, list.size(), "每个测试方法一个新用户, 所以列表里只有它自己建的那一个会话");
        JsonNode row = list.get(0);
        assertNotNull(row.get("peerName"), "peerName 宁可退回会话标题也不该是 null");
        return row;
    }

    private JsonNode readJson(String body) throws Exception {
        return objectMapper.readTree(body);
    }
}
