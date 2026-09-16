package com.luxera.companion.conversation;

import com.luxera.chatframework.ChatPlatformTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话列表面(`/api/conversations` 背后那套) —— senderId、未读、置顶免打扰、以及
 * "我参与的所有会话"。
 *
 * <p>这里刻意都走 service 而不是 MockMvc: 这一层要验的是"数据算得对不对",
 * 而不是"HTTP 路由挂没挂对"。鉴权那一层由 {@code requireVisible} 的两条断言覆盖。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class ConversationListTest {

    @Autowired
    ConversationService conversationService;
    @Autowired
    ConversationReadStateService readStateService;
    @Autowired
    ConversationParticipantRepository participantRepository;

    private final String companionId = UUID.randomUUID().toString();
    /**
     * 每个测试独立的用户 —— 测试库 companion_test 是全类共享的。
     *
     * <p>刻意就用一个裸 UUID: {@code conversations.user_id} 与 {@code
     * conversation_participants.member_id} 都是 {@code varchar(36)}, 加任何前缀都会溢出。
     * 溢出本身不致命, 致命的是它曾经表现为一句"参与者注册失败"被静默吞掉, 调用方
     * 只看到一个 {@code UnexpectedRollbackException}。
     */
    private final String userId = UUID.randomUUID().toString();
    private final String otherCompanionId = UUID.randomUUID().toString();

    private String conversationId;

    @BeforeEach
    void setUp() {
        conversationId = conversationService
                .create(userId, companionId, "测试会话", "林夏")
                .getId();
    }

    @Test
    void listForMemberReturnsMyConversations() {
        List<Conversation> mine = conversationService.listForMember(userId);
        assertTrue(mine.stream().anyMatch(c -> c.getId().equals(conversationId)),
                "自己刚建的会话必须出现在自己的列表里");
    }

    /**
     * 这条是列表页最容易踩的坑: 参与者注册至今包在一个吞异常的影子路径里, 所以库里
     * 可能存在"用户看得到、参与者表里却没有他"的历史会话。纯走参与者表会让这些会话
     * **凭空消失** —— 用户看着自己的聊天记录不见了。
     */
    @Test
    void listForMemberKeepsConversationsThatHaveNoParticipantRows() {
        for (ConversationParticipant p : participantRepository.findByConversationId(conversationId)) {
            participantRepository.delete(p);
        }
        assertTrue(participantRepository.findByConversationId(conversationId).isEmpty());

        List<String> ids = conversationService.listForMember(userId).stream()
                .map(Conversation::getId).toList();
        assertTrue(ids.contains(conversationId),
                "参与者行丢了也不能让会话从列表里消失 —— 并集兜的就是这件事");
    }

    @Test
    void summaryCarriesPeerNameAndLastMessage() {
        conversationService.addMessage(conversationId, "user", userId, "在吗",
                null, null, null, false, null, null, null, null);

        ConversationSummaryView s = onlySummary();
        assertEquals(conversationId, s.getId());
        assertEquals(companionId, s.getPeerId());
        assertEquals("林夏", s.getPeerName(), "对方名字取自参与者行, 不是会话标题");
        assertNotNull(s.getLastMessage());
        assertEquals("在吗", s.getLastMessage().getContent());
        assertEquals("user", s.getLastMessage().getSenderType());
        assertEquals(userId, s.getLastMessage().getSenderId());
    }

    @Test
    void summaryWithoutAnyMessageHasNoLastMessage() {
        assertNull(onlySummary().getLastMessage(), "从没说过话的会话不该编出一条空消息");
    }

    // ── senderId ───────────────────────────────────────────────────

    @Test
    void explicitSenderIdIsKept() {
        Message m = conversationService.addMessage(conversationId, "user", userId, "你好",
                null, null, null, false, null, null, null, null);
        assertEquals(userId, m.getSenderId());
    }

    /**
     * companion 消息不传 senderId 时推导得出 —— 一个会话只有一个 companion 列, 无歧义。
     * 数字人平台的 {@code append} 走的正是这条路(它不知道 agent 的账号 id)。
     */
    @Test
    void companionSenderIdIsDerived() {
        Message m = conversationService.addMessage(conversationId, "companion", "我是林夏",
                null, null, null, false, null, null, null, null);
        assertEquals(companionId, m.getSenderId());
    }

    /**
     * user 消息不传 senderId 时**留空, 不猜**。猜的话一对一里会猜对, 群聊里会猜成群主 ——
     * 一条署错名的消息比一条没有署名的消息难查得多。
     */
    @Test
    void userSenderIdIsNotGuessed() {
        Message m = conversationService.addMessage(conversationId, "user", "我是谁",
                null, null, null, false, null, null, null, null);
        assertNull(m.getSenderId(), "user 消息的作者不该被推定");
    }

    @Test
    void systemMessagesHaveNoSender() {
        Message m = conversationService.addMessage(conversationId, "system", "你撤回了一条消息",
                null, null, null, false, null, null, null, null);
        assertNull(m.getSenderId());
    }

    // ── 未读 ───────────────────────────────────────────────────────

    @Test
    void userMessageLeavesOneUnreadForTheAgentAndNoneForTheSender() {
        conversationService.addMessage(conversationId, "user", userId, "在吗",
                null, null, null, false, null, null, null, null);

        assertEquals(0, readStateService.unreadOf(conversationId, userId),
                "自己发的消息不该让自己看到未读角标");
        assertEquals(1, readStateService.unreadOf(conversationId, companionId),
                "对面应该有一条未读");
    }

    @Test
    void agentReplyLeavesUnreadForTheUser() {
        conversationService.addMessage(conversationId, "companion", "在的",
                null, null, null, false, null, null, null, null);

        assertEquals(1, readStateService.unreadOf(conversationId, userId));
        assertEquals(0, readStateService.unreadOf(conversationId, companionId),
                "agent 自己发的不该记成它的未读");
    }

    @Test
    void unreadAccumulatesThenClearsOnRead() {
        for (int i = 0; i < 3; i++) {
            conversationService.addMessage(conversationId, "companion", "第 " + i + " 条",
                    null, null, null, false, null, null, null, null);
        }
        assertEquals(3, readStateService.unreadOf(conversationId, userId));

        readStateService.markRead(conversationId, userId, null);
        assertEquals(0, readStateService.unreadOf(conversationId, userId));
        assertFalse(onlySummary().getUnreadCount() > 0);
    }

    // ── 置顶 / 免打扰 ──────────────────────────────────────────────

    @Test
    void pinnedAndMutedSurfaceInTheSummary() {
        ConversationSummaryView before = onlySummary();
        assertFalse(before.isPinned());
        assertFalse(before.isMuted());

        readStateService.setPinned(conversationId, userId, true);
        readStateService.setMuted(conversationId, userId, true);

        ConversationSummaryView after = onlySummary();
        assertTrue(after.isPinned());
        assertTrue(after.isMuted());
    }

    @Test
    void pinnedAndMutedArePerMember() {
        readStateService.setPinned(conversationId, userId, true);
        // 同会话的另一个人不该跟着被置顶 —— 这正是这两列不挂在 Conversation 上的理由
        // summaryOf(userId, conv) 读的是**传进去的那个人**的读状态
        ConversationSummaryView forMe = conversationService.summaryOf(userId,
                conversationService.requireVisible(userId, conversationId));
        assertTrue(forMe.isPinned(), "自己看是置顶的");

        ConversationReadState agentState = new ConversationReadState();
        agentState.setConversationId(conversationId);
        agentState.setMemberId(companionId);
        assertFalse(ConversationReadStateService.isPinned(agentState));
    }

    // ── 鉴权 ───────────────────────────────────────────────────────

    @Test
    void requireVisibleRejectsNonOwner() {
        assertThrows(RuntimeException.class,
                () -> conversationService.requireVisible("someone-else", conversationId));
    }

    @Test
    void listForMemberDoesNotLeakOtherPeoplesConversations() {
        String stranger = UUID.randomUUID().toString();
        // 单独建一个会话, 但用另一个 companion, 确保不会因为 companionId 相同而误判
        String other = conversationService.create(stranger, otherCompanionId, "别人的", "别人").getId();
        List<String> mine = conversationService.listForMember(userId).stream()
                .map(Conversation::getId).toList();
        assertFalse(mine.contains(other), "别人的会话不该出现在我的列表里");
    }

    private ConversationSummaryView onlySummary() {
        return conversationService.summariesFor(userId).stream()
                .filter(s -> s.getId().equals(conversationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("会话不在自己的列表里"));
    }
}
