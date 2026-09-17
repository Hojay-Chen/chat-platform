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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除一个 peer ⇒ 它的会话连消息一起消失 —— 用户 2026-09-17 报的那个 bug 的回归测试。
 *
 * <h2>它守的是什么</h2>
 *
 * 症状: 用户的聊天列表里出现几十个与"同一个 agent"的聊天窗口。底下的事实是他名下有
 * 47 个 Agent(9 个活着、38 个已删除), 45 段会话里 <b>36 段的主人是已删除的 Agent</b>;
 * 而名字是 LLM 从描述里生成的, 反复收敛到同一个(「小满」), 所以看起来像"同一个账号
 * 开了 36 个窗口"。
 *
 * 根因不在本类要测的这段代码里, 而在**没人调用它**: 通讯录问的是仿真 Agent 平台
 * (SQL 带 {@code deleted_at is null}), 聊天列表问的是本仓自己的会话表(只问"我是不是
 * 参与者")。删 Agent 只写了 {@code deleted_at}, 聊天这边从来不知道。
 *
 * <p>所以这一组断言真正钉住的是那个**契约**: 一旦有人告诉你这个 peer 没了, 本仓必须
 * 能把它清得干干净净, 而且是**只**清它 —— 清错邻居比不清更糟, 因为那是数据丢失。
 *
 * <p>刻意都走 service 不走 MockMvc: HTTP 那一层要验的只是"端点挂没挂对", 由
 * {@code InternalEndpointSecurityTest} 覆盖鉴权, 这里要验的是"删得对不对"。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class ConversationPurgeTest {

    @Autowired
    ConversationService conversationService;
    @Autowired
    ConversationPurgeService purgeService;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    ConversationParticipantRepository participantRepository;
    @Autowired
    ConversationReadStateService readStateService;

    private final String userId = UUID.randomUUID().toString();
    /** 要被删掉的那个 Agent */
    private final String doomedPeer = UUID.randomUUID().toString();
    /** 隔壁那个必须活下来的 Agent —— 每一条断言都要带上它 */
    private final String survivingPeer = UUID.randomUUID().toString();

    private String doomedConvId;
    private String survivingConvId;

    @BeforeEach
    void setUp() {
        doomedConvId = conversationService.create(userId, doomedPeer, "要被删的", "小满").getId();
        survivingConvId = conversationService.create(userId, survivingPeer, "要留下的", "阿澈").getId();

        conversationService.addMessage(doomedConvId, "user", userId, "在吗", null, null, null,
                false, null, null, null, null);
        conversationService.addMessage(doomedConvId, "companion", null, "在的", null, null, null,
                false, null, null, null, null);
        conversationService.addMessage(survivingConvId, "user", userId, "别删我", null, null, null,
                false, null, null, null, null);

        readStateService.markRead(doomedConvId, userId, null);
        readStateService.setPinned(doomedConvId, userId, true);
        // 隔壁也要有一条读状态行, 否则"隔壁没被殃及"就是空断言 —— 它本来就是空的
        readStateService.markRead(survivingConvId, userId, null);
    }

    @Test
    void purgedConversationAndItsMessagesAreGone() {
        assertTrue(messageRepository.countByConversationId(doomedConvId) > 0, "前提: 这段会话本来有消息");

        List<String> purged = purgeService.purgePeer(doomedPeer);

        assertTrue(purged.contains(doomedConvId), "返回值必须包含被销毁的会话 id —— 仓 2 要靠它删 session_summaries");
        assertFalse(conversationRepository.findById(doomedConvId).isPresent(), "会话本身必须没了");
        assertEquals(0, messageRepository.countByConversationId(doomedConvId), "消息必须连根拔掉, 不是留个墓碑");
    }

    @Test
    void theReadStateRowGoesToo() {
        // 读状态/置顶是挂在 (conversationId, memberId) 上的 —— 会话没了它还留着,
        // 下次同一个 conversationId 被复用时(概率低但不是零)会直接继承上一次的已读位置。
        assertTrue(readStateService.statesOf(userId).containsKey(doomedConvId), "前提: 读状态行已建");

        purgeService.purgePeer(doomedPeer);

        assertFalse(readStateService.statesOf(userId).containsKey(doomedConvId),
                "读状态行必须跟着会话一起消失");
    }

    @Test
    void theParticipantRowsGoToo() {
        // 参与者表是二期的鉴权主路径(requireVisible 已经按它问"我是不是参与者")。
        // 留着它等于留着"我还看得见一段不存在的会话"。
        assertTrue(participantRepository.findByConversationIdIn(List.of(doomedConvId)).size() > 0,
                "前提: 建会话时注册了参与者");

        purgeService.purgePeer(doomedPeer);

        assertEquals(0, participantRepository.findByConversationIdIn(List.of(doomedConvId)).size(),
                "参与者行必须跟着会话一起消失");
    }

    /**
     * ★ 这一条是整组里最要紧的: <b>只</b>删该删的那个。
     *
     * <p>清错邻居比不清更糟 —— 前者是数据丢失, 后者只是脏。而"按 companionId 删"这种
     * 写法最典型的错法就是少写一个条件, 于是把整个用户的会话全带走。
     */
    @Test
    void theNeighbouringPeerIsUntouched() {
        purgeService.purgePeer(doomedPeer);

        assertTrue(conversationRepository.findById(survivingConvId).isPresent(), "隔壁会话必须还在");
        assertEquals(1, messageRepository.countByConversationId(survivingConvId), "隔壁的消息一条都不能少");
        assertTrue(participantRepository.findByConversationIdIn(List.of(survivingConvId)).size() > 0,
                "隔壁的参与者行也必须在");
        assertTrue(readStateService.statesOf(userId).containsKey(survivingConvId),
                "隔壁的读状态不该被殃及");
    }

    /** 删两次的第二次不该失败 —— 调用方在"上次删到一半"时正是靠重放来完成收尾的。 */
    @Test
    void purgingTwiceIsSafe() {
        purgeService.purgePeer(doomedPeer);

        List<String> second = purgeService.purgePeer(doomedPeer);

        assertEquals(List.of(), second, "第二次应当是空清单, 而不是异常");
    }

    /** 一个从来没有过会话的 peer —— 不该在生产上炸(它可能就是刚铸出来还没说过话的 Agent)。 */
    @Test
    void purgingAPeerWithNoConversationsIsANoOp() {
        assertEquals(List.of(), purgeService.purgePeer(UUID.randomUUID().toString()));
    }

    @Test
    void countReportsTheBlastRadiusWithoutWriting() {
        assertEquals(1, purgeService.conversationCountOf(doomedPeer));

        // 只读的那个方法不该有副作用 —— 它存在的意义就是"删之前先看看要毁掉多少"
        assertEquals(1, purgeService.conversationCountOf(doomedPeer));
        assertNotNull(conversationRepository.findById(doomedConvId).orElse(null));
    }
}
