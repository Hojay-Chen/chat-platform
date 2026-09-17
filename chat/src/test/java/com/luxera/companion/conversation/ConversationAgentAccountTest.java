package com.luxera.companion.conversation;

import com.luxera.chatframework.ChatPlatformTestApplication;
import com.luxera.companion.simulator.server.SimulatorPairingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **有聊天账号**的 Agent 在会话里的身份 —— 也就是这次"账号即身份"改造之后的主路径。
 *
 * <h2>为什么这个测试必须单独存在</h2>
 *
 * 因为 {@code ConversationListTest} 里的每一条断言今天仍然全绿, 而它们**全都走的是回退
 * 那一支**: 那些用例建的 companion 没有设备、没有账号, 于是 {@code AgentChatIdentity}
 * 返回 null, 一切退回 {@code companionId} —— 行为与改造之前逐字相同。
 *
 * <p>也就是说: 那 30 条用例证明的是"改造没有破坏老数据", 而这个文件证明的是"新数据走上了
 * 新路"。少了它, 改造里最危险的那一半(参与者 member_id 与消息 sender_id 是否停在同一个
 * 命名空间)就没有任何断言 —— 而那一半错了**不报错**, 只表现为"Agent 自己发的消息给自己涨
 * 未读", 或者"Agent 连上 WS 看不到任何会话"。
 *
 * <h2>这里刻意用真的 {@code SimulatorPairingService} 铸账号</h2>
 *
 * 直接 new 一个 {@code SimulatorDevice} 塞进库更短, 但那样就绕过了
 * {@code attachCompanion}(写绑定的那一步)—— 而"设备绑定"正是
 * {@code SimulatorAgentChatIdentity} 唯一的判据。用真服务意味着这个测试同时覆盖了
 * "铸号 → 绑定 → 建会话"这条链真的接得上。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class ConversationAgentAccountTest {

    @Autowired
    ConversationService conversationService;
    @Autowired
    ConversationReadStateService readStateService;
    @Autowired
    ConversationParticipantRepository participantRepository;
    @Autowired
    SimulatorPairingService pairingService;

    /** 每个用例独立的 id —— 测试库是全类共享的 */
    private final String userId = UUID.randomUUID().toString();
    private final String companionId = UUID.randomUUID().toString();

    @Test
    void conversationOfAnAgentWithAccountCarriesTheAccountEverywhere() {
        var account = provision("林夏");

        Conversation conv = conversationService.create(userId, companionId, "测试会话", "林夏");

        assertEquals(account.accountId(), conv.getAgentAccountId(),
                "会话上记的必须是聊天账号 id, 不是 companionId");

        ConversationParticipant agent = onlyAgentRow(conv.getId());
        assertEquals(account.accountId(), agent.getMemberId(),
                "agent 参与者的 member_id 必须是聊天账号 id");
        assertEquals("林夏", agent.getDisplayName(), "改名不能把展示名弄丢");

        // 消息的 sender_id 与参与者的 member_id 必须是同一个值 —— 这一条是整套改造成立的前提
        Message m = conversationService.addMessage(conv.getId(), "companion", "在的",
                null, null, null, false, null, null, null, null);
        assertEquals(account.accountId(), m.getSenderId());
        assertEquals(agent.getMemberId(), m.getSenderId(),
                "参与者与消息的命名空间必须一致, 否则未读判定永远为假");
    }

    /**
     * 这条是需求④ 真正跑得起来的证据, 也是缺陷 6.3.1 的回归测试。
     *
     * <p>在改造之前, {@code chat.listConversations} 走的是
     * {@code conversationService.list(accountId, companionId)} —— 判据是
     * {@code Conversation.user_id = accountId}, 而那一列**永远是真人**。于是 Agent 用自己
     * 的账号连上 WS 之后, 会看到**空的会话列表**: 它看不到自己要参与的任何会话, 而它也不会
     * 报错, 只会安安静静地什么都不说。
     */
    @Test
    void agentSeesTheConversationItParticipatesIn() {
        var account = provision("林夏");
        Conversation conv = conversationService.create(userId, companionId, "测试会话", "林夏");

        List<String> seen = conversationService.listForMember(account.accountId()).stream()
                .map(Conversation::getId).toList();

        assertTrue(seen.contains(conv.getId()),
                "Agent 用自己账号查会话时必须看得到它参与的那一段 —— 这是 listForMember 存在的理由");
        // 反面: 真人那一侧也要看得到, 两个人看同一段会话
        assertTrue(conversationService.listForMember(userId).stream()
                .anyMatch(c -> c.getId().equals(conv.getId())));
    }

    /**
     * Agent 自己发的消息不该给它自己涨未读 —— 这条断言在改造之前**也是绿的**, 因为那时
     * 参与者和消息都用 companionId。它在这里守的是改造之后仍然绿。
     */
    @Test
    void agentDoesNotGetUnreadFromItsOwnMessage() {
        var account = provision("林夏");
        Conversation conv = conversationService.create(userId, companionId, "测试会话", "林夏");

        conversationService.addMessage(conv.getId(), "companion", "在的",
                null, null, null, false, null, null, null, null);

        assertEquals(1, readStateService.unreadOf(conv.getId(), userId), "真人该有一条未读");
        assertEquals(0, readStateService.unreadOf(conv.getId(), account.accountId()),
                "Agent 自己发的不该记成它的未读 —— 记上了就是一个永远消不掉的红点");
    }

    /**
     * 「打开一段老会话」时顺手把身份修正过来。
     *
     * <p>这里模拟的是回填 runner 管不到的那个窗口: 会话先建出来(那时还没有账号), 之后设备才
     * 绑上。第二次 {@code ensureConversation} 应当把它就地切过去。
     *
     * <p><b>就地</b>这两个字是要断言的: 参与者行不只要改成新 id, 它的 {@code joined_at}
     * 还必须**没变**。删掉重插也能让 member_id 变对, 但会把"她什么时候进的这段会话"重置
     * 成现在 —— 一个两年前的会话里出现一个今天刚加入的参与者。
     */
    @Test
    void ensureConversationRepointsAnExistingConversationWhenTheAccountAppears() {
        Conversation conv = conversationService.create(userId, companionId, "旧会话", "阿澈");
        assertNull(conv.getAgentAccountId(), "还没有账号时不该编一个出来");
        var before = onlyAgentRow(conv.getId());
        assertEquals(companionId, before.getMemberId(), "没有账号时参与者退回 companionId");

        provision("阿澈");   // 铸号 + 绑定, 也就是回填 runner 做的那一步

        Conversation again = conversationService.ensureConversation(userId, companionId, "阿澈");

        assertNotNull(again.getAgentAccountId(), "账号出现之后该会话必须被切过去");
        List<ConversationParticipant> agents = agentRows(again.getId());
        assertEquals(1, agents.size(),
                "一个会话里只能有一个 agent 参与者 —— 补一行而不是改一行会造出两个");
        assertEquals(again.getAgentAccountId(), agents.get(0).getMemberId());
        assertEquals(before.getJoinedAt(), agents.get(0).getJoinedAt(),
                "改成新 id 必须是就地改, 不能把加入时间重置成现在");
    }

    /**
     * 修正过之后再打开多少次都不该再动 —— 否则每打开一次会话就多一次写库。
     */
    @Test
    void ensureConversationIsIdempotentOnceRepaired() {
        provision("阿澈");
        conversationService.create(userId, companionId, "旧会话", "阿澈");
        Conversation first = conversationService.ensureConversation(userId, companionId, "阿澈");
        String accountId = first.getAgentAccountId();
        var joinedAt = onlyAgentRow(first.getId()).getJoinedAt();

        Conversation second = conversationService.ensureConversation(userId, companionId, "阿澈");

        assertEquals(accountId, second.getAgentAccountId());
        assertEquals(1, agentRows(second.getId()).size());
        assertEquals(joinedAt, onlyAgentRow(second.getId()).getJoinedAt());
    }

    // ── 辅助 ───────────────────────────────────────────────────────

    /** 铸一个聊天账号并把它绑到本类那个 companion 上 —— 回填 runner 做的两步。 */
    private SimulatorPairingService.ProvisionResult provision(String name) {
        SimulatorPairingService.ProvisionResult pr = pairingService.provisionSimulatorAccount(name, null);
        pairingService.attachCompanion(pr.deviceId(), companionId);
        return pr;
    }

    private List<ConversationParticipant> agentRows(String conversationId) {
        return participantRepository.findByConversationId(conversationId).stream()
                .filter(p -> ConversationParticipant.ROLE_AGENT.equals(p.getRole()))
                .toList();
    }

    private ConversationParticipant onlyAgentRow(String conversationId) {
        List<ConversationParticipant> rows = agentRows(conversationId);
        assertEquals(1, rows.size(), "本用例的会话应当恰好有一个 agent 参与者");
        return rows.get(0);
    }
}
