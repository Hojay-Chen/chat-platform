package com.luxera.companion.provisioning;

import com.luxera.chatserver.ChatPlatformApplication;
import com.luxera.companion.common.BusinessException;
import com.luxera.companion.config.JwtUtil;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.contracts.provision.AgentRegistrationPort;
import com.luxera.companion.contracts.provision.AgentRegistrationRequest;
import com.luxera.companion.contracts.provision.RegisteredAgent;
import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.simulator.server.SimulatorDevice;
import com.luxera.companion.simulator.server.SimulatorDeviceRepository;
import com.luxera.companion.simulator.server.SimulatorPairingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一键创建 Agent 好友 —— 需求 ④ 的那条编排。
 *
 * <h2>这个测试要守的三件事, 每一件都是"错了不会报错"的那种</h2>
 *
 * <ol>
 *   <li><b>一个 requestId 只铸一个聊天账号。</b>重试在一键创建里是常态(用户双击、前端超时
 *       重发), 而铸出第二个账号不违反任何唯一约束 —— 它只是多了一行 {@code users} 和一个
 *       永远不会有人用的 device。所以断言落在"第二次调用拿到的是同一个 accountId"上。</li>
 *   <li><b>跨平台调用失败之后, 那台设备必须不能配对。</b>失败若不补偿, 用户会拿到一个
 *       配对码, 而对面根本没有对应的 agent —— 一个"能连上但永远说不了话"的账号,
 *       而且他没有任何办法把它清掉。</li>
 *   <li><b>重试要走回同一个账号上。</b>补偿把设备吊销了, 但这不代表账号可以换一个:
 *       对面仓 2 的 {@code companions.chat_account_id} 唯一索引记得它, 换个账号就会绕过
 *       那个唯一键、铸出第二份。</li>
 * </ol>
 *
 * <h2>桩为什么不是"返回一个固定值"</h2>
 *
 * 因为那样就测不出第一条。这里的桩**自己实现了对面那台服务的唯一约束**
 * ({@code chat_account_id} 唯一 → 同一个聊天账号只会得到一个 agent), 于是"仓 1 铸了第二个
 * 聊天账号"这件事会表现为"拿到第二个 agentId", 而断言第一眼看的是 accountId ——
 * 两条路都堵上。桩若返回常量, 这个 bug 在断言里是隐形的。
 *
 * <p>用真实启动类({@code ChatPlatformApplication})而不是 {@code ChatPlatformTestApplication},
 * 因为后者刻意把扫描收紧到聊天三包, 不含 {@code provisioning}; 而本端点必须验的是
 * **过滤器链上的可达性**(JWT 那层), 不是 service 的算术。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformApplication.class)
@Import(AgentFriendProvisioningTest.FakeAgentPlatform.class)
@AutoConfigureMockMvc
class AgentFriendProvisioningTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    UserRepository userRepo;
    @Autowired
    AgentFriendProvisioningService provisioning;
    @Autowired
    SimulatorPairingService pairingService;
    @Autowired
    SimulatorDeviceRepository deviceRepo;
    @Autowired
    ConversationService conversationService;
    @Autowired
    FakeRegistration fake;

    String ownerId;

    @BeforeEach
    void setUp() {
        fake.reset();
        ownerId = newUser("friend-owner").getId();
    }

    // ── 编排本身 ────────────────────────────────────────────────────────────

    @Test
    void oneClickLandsAllFourSteps() {
        String requestId = UUID.randomUUID().toString();
        var created = provisioning.create(ownerId, requestId, "一个温柔的插画师朋友", null, "friend");

        // 第一步 + 第二步: 一个 SIMULATOR 聊天账号
        assertNotNull(created.chatAccountId());
        User sim = userRepo.findById(created.chatAccountId()).orElseThrow();
        assertEquals("SIMULATOR", sim.getUserKind());

        // 第三步: agent 与它的账号ID
        assertNotNull(created.agentId());
        assertEquals(created.chatAccountId(), fake.lastRequest().chatAccountId());
        assertEquals(ownerId, fake.lastRequest().ownerUserId(), "agent 必须归发起这次操作的真人所有");

        // 第四步: 回绑 + 会话进通讯录
        SimulatorDevice dev = deviceRepo.findById(deviceIdOf(requestId)).orElseThrow();
        assertEquals(created.agentId(), dev.getCompanionId());
        assertEquals(requestId, dev.getRequestId());
        assertFalse(conversationService.list(ownerId, created.agentId()).isEmpty(),
                "建完好友要能在通讯录里看见它 —— 那正是「一键创建好友」的后两个字");

        // 展示名最后被 agent 的真名覆盖(建账号时还不知道 agent 叫什么)
        assertEquals(created.name(), sim.getDisplayName());
    }

    /**
     * 返回的配对码得真的能用 —— 否则这条链的产出是一张废纸。
     */
    @Test
    void theReturnedPairingCodeActuallyPairsTheDevice() {
        var created = provisioning.create(ownerId, UUID.randomUUID().toString(), "会下棋的朋友", null, null);

        assertNotNull(created.pairingCode(), "首次创建必须给出配对码");
        var done = pairingService.completePairing(created.pairingCode());
        assertEquals(created.chatAccountId(), done.accountId());
        assertNotNull(done.secret());
        // 配对之后绑定关系还在 —— 配对只是换凭据, 不是重新认人
        assertEquals(created.agentId(), deviceRepo.findById(done.deviceId()).orElseThrow().getCompanionId());
    }

    @Test
    void replayingTheSameRequestIdMintsNothingNew() {
        String requestId = UUID.randomUUID().toString();
        var first = provisioning.create(ownerId, requestId, "同一个意图", null, null);
        var second = provisioning.create(ownerId, requestId, "同一个意图", null, null);

        assertEquals(first.chatAccountId(), second.chatAccountId(), "重放不该铸出第二个聊天账号");
        assertEquals(first.agentId(), second.agentId(), "重放不该建出第二个 agent");
        assertEquals(first.handle(), second.handle());
        assertEquals(1, devicesWithRequestId(requestId), "一个 requestId 只能有一台设备");
        assertEquals(1, fake.agentCount(), "桩这侧也只该有一个 agent");
    }

    @Test
    void aFailedRegistrationRevokesTheDeviceButKeepsTheAccount() {
        String requestId = UUID.randomUUID().toString();
        fake.failNext = true;

        BusinessException e = assertThrows(BusinessException.class,
                () -> provisioning.create(ownerId, requestId, "建不成的那种", null, null));
        // 失败要原样抛出来让用户看见(而不是吞掉回一个空成功)
        assertNotNull(e.getMessage());

        SimulatorDevice dev = deviceRepo.findById(deviceIdOf(requestId)).orElseThrow();
        assertEquals(SimulatorPairingService.STATUS_REVOKED, dev.getStatus(),
                "agent 没建成 → 这台设备必须不能配对, 否则用户拿到一个永远说不了话的账号");
        assertNull(dev.getCompanionId());

        // 但账号**留着**: 账号ID 一旦发出去就不可回收, 删了还会留下悬垂的 account_id
        assertTrue(userRepo.findById(dev.getAccountId()).isPresent(),
                "补偿只吊销设备, 不删聊天账号");
        // 吊销的码不能再换 secret —— 这正是"不能配对"的实测
        assertThrows(IllegalArgumentException.class,
                () -> pairingService.completePairing(dev.getPairingCode()));
    }

    @Test
    void retryingAfterAFailureResumesOnTheSameAccount() {
        String requestId = UUID.randomUUID().toString();
        fake.failNext = true;
        assertThrows(BusinessException.class,
                () -> provisioning.create(ownerId, requestId, "先失败一次", null, null));
        SimulatorDevice failed = deviceRepo.findById(deviceIdOf(requestId)).orElseThrow();
        String firstCode = failed.getPairingCode();

        fake.failNext = false;
        var retried = provisioning.create(ownerId, requestId, "先失败一次", null, null);

        assertEquals(failed.getAccountId(), retried.chatAccountId(),
                "重试必须回到同一个聊天账号 —— 对面那个唯一索引记得它");
        SimulatorDevice revived = deviceRepo.findById(failed.getDeviceId()).orElseThrow();
        assertEquals(SimulatorPairingService.STATUS_PAIRING, revived.getStatus(), "复活同一台设备");
        assertNotEquals(firstCode, revived.getPairingCode(), "码换新 —— 旧的那个已经被吊销了");
        assertEquals(retried.agentId(), revived.getCompanionId());
    }

    // ── HTTP 面: 它落在过滤器链后面吗, 归属是谁说了算 ─────────────────────────

    @Test
    void theEndpointIsBehindTheLoginFilter() throws Exception {
        // 403 而不是 401: 这是 Spring Security 在**没有** AuthenticationEntryPoint 时的默认
        // 形状, 与本仓其他受保护端点一致(见 ConversationListHttpTest 的同一断言)。
        mockMvc.perform(post("/api/agent-friends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"x\",\"description\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRequestWithoutARequestIdIsRefused() throws Exception {
        mockMvc.perform(post("/api/agent-friends")
                        .header("Authorization", "Bearer " + token(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"没有幂等键\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("requestId")));
    }

    /**
     * <b>归属只由 JWT 决定 —— 请求体里塞一个 ownerUserId 是无效的。</b>
     *
     * <p>这条断言看着像在测 Jackson 的宽容, 其实测的是一条安全边界: 请求体里
     * <b>根本没有</b>这个字段({@code CreateBody} 只有四个), 所以任何登录用户都无法把
     * agent 建到别人的通讯录里。哪天有人"顺手"往 {@code CreateBody} 加了它并接上去,
     * 这条用例就会红。
     */
    @Test
    void theRequestBodyCannotChooseTheOwner() throws Exception {
        String someoneElse = newUser("victim").getId();

        mockMvc.perform(post("/api/agent-friends")
                        .header("Authorization", "Bearer " + token(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\","
                                + "\"description\":\"塞给别人的好友\","
                                + "\"ownerUserId\":\"" + someoneElse + "\"}"))
                .andExpect(status().isCreated());

        assertEquals(ownerId, fake.lastRequest().ownerUserId(),
                "agent 归登录的那个人, 不归请求体里写的那个");
    }

    /**
     * 回执里那三个 id 是**互不相同**的三件东西, 且都得在。
     *
     * <p>这条断言不是形式主义: 这三个值在实现里分别来自
     * {@code companions.id} / {@code users.id} / {@code persons.handle}, 而它们**曾经**
     * 有一处是同一个值({@code Person.id} 对 AGENT 行直接复用了 {@code companions.id})。
     * 三者里有任意两个碰巧相等, 都说明又回到了那个状态。
     */
    @Test
    void theHappyPathAnswersWithThreeDistinctIds() throws Exception {
        String body = mockMvc.perform(post("/api/agent-friends")
                        .header("Authorization", "Bearer " + token(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\","
                                + "\"description\":\"一个爱讲冷笑话的朋友\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentId").isNotEmpty())
                .andExpect(jsonPath("$.chatAccountId").isNotEmpty())
                .andExpect(jsonPath("$.handle").isNotEmpty())
                .andExpect(jsonPath("$.pairingCode").isNotEmpty())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        String agentId = json.get("agentId").asText();
        String chatAccountId = json.get("chatAccountId").asText();
        String handle = json.get("handle").asText();
        assertNotEquals(agentId, chatAccountId, "agent id 与聊天账号 id 是两个平台的标识, 不能相同");
        assertNotEquals(agentId, handle);
        assertNotEquals(chatAccountId, handle);
        // 不在这里断言 handle 的 agent_ 前缀: 这个测试里 handle 是本文件那个桩造的,
        // 断言它等于在测自己。前缀是仓 2 的性质, 由那边的 HandlesTest 守。
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private String token(String userId) {
        return jwtUtil.generateToken(userId, "agent-friend-test");
    }

    private User newUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        u.setPasswordHash("x");
        u.setEmail(prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local");
        return userRepo.save(u);
    }

    private String deviceIdOf(String requestId) {
        return deviceRepo.findByRequestId(requestId).orElseThrow().getDeviceId();
    }

    private long devicesWithRequestId(String requestId) {
        return deviceRepo.findAll().stream().filter(d -> requestId.equals(d.getRequestId())).count();
    }

    /**
     * 仿真 Agent 平台 openAPI 的桩 —— {@code AgentRegistrationPort} 的真实实现在另一个仓库,
     * 单测不该真的去连 8092。
     *
     * <p>标 {@code @Primary} 盖掉常驻的 {@code HttpAgentRegistrationAdapter}。
     */
    @TestConfiguration
    static class FakeAgentPlatform {
        @Bean
        @Primary
        FakeRegistration fakeAgentRegistration() {
            return new FakeRegistration();
        }
    }

    static class FakeRegistration implements AgentRegistrationPort {

        private final Map<String, RegisteredAgent> byChatAccount = new HashMap<>();
        private AgentRegistrationRequest last;
        boolean failNext = false;

        void reset() {
            byChatAccount.clear();
            last = null;
            failNext = false;
        }

        AgentRegistrationRequest lastRequest() {
            return last;
        }

        int agentCount() {
            return byChatAccount.size();
        }

        @Override
        public RegisteredAgent register(AgentRegistrationRequest request) {
            last = request;
            if (failNext) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "Agent 平台暂不可达", null);
            }
            // 忠实模拟对面那条唯一索引: 同一个 chatAccountId 只会得到一个 agent。
            // 桩若返回常量, "仓 1 铸了第二个聊天账号"这个 bug 就是隐形的 —— 见类注释。
            return byChatAccount.computeIfAbsent(request.chatAccountId(),
                    acc -> new RegisteredAgent(UUID.randomUUID().toString(), acc,
                            "agent_" + UUID.randomUUID().toString().substring(0, 8), "小满"));
        }
    }
}
