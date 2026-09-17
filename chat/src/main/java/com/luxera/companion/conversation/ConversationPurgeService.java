package com.luxera.companion.conversation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 一个 peer 没了 —— 把聊天平台这边跟它有关的东西全部清掉。
 *
 * <h2>它为什么存在: 两个列表对"存在"的判据曾经不一致</h2>
 *
 * 2026-09-17 用户报了一个症状: 「为什么我的账号的聊天界面会出现多个与同一个仿真 agent
 * 的账号的聊天窗口?」查下来的事实是 —— 他名下有 47 个 Agent(9 个活着、38 个已删除),
 * 45 段会话, 其中 <b>36 段的主人是已删除的 Agent</b>。
 *
 * 两个列表问的是两个地方:
 * <ul>
 *   <li><b>通讯录</b>问仿真 Agent 平台, SQL 里带着 {@code deleted_at is null} —— 删掉的
 *       Agent 立刻消失。</li>
 *   <li><b>聊天</b>问本仓自己的会话表({@code ConversationService#listForMember}), 它只问
 *       "我是不是这段会话的参与者", <b>从来不问对方还在不在</b> —— 而它也不该去问: 按本仓
 *       自己的说法, chat "不知道对面是个数字人"(见 {@code ConversationService} 的类注释)。
 *       peer 在这里只是一个不透明的字符串。</li>
 * </ul>
 *
 * 于是删一个 Agent 的可见效果只有一半: 它从通讯录消失, 它那段会话永远留在聊天里。删了 38 次,
 * 聊天里攒下 36 个幽灵窗口 —— 而名字又是 LLM 从描述里生成的, 反复收敛到同一个(「小满」),
 * 所以看起来像"同一个账号开了 36 个窗口", 而不是"36 个已删除的 Agent 留下的痕迹"。
 *
 * <h2>为什么由本仓执行, 而不是让仿真 Agent 平台直接删表</h2>
 *
 * 这些表是 chat 的。{@code conversations} 这一族(消息、参与者、读状态、exchange、thread、
 * boundary、会话段)全部由本仓拥有; 仿真 Agent 平台拥有 {@code phone_notifications} /
 * {@code pending_message_states} / {@code session_summaries}。两个服务共用同一个 Postgres,
 * 但"共用库"不是"可以互相写表"的理由 —— 那会让"谁能写什么"变成一份没人维护的口头约定。
 *
 * 所以切法按**归属**而不是按**方便**: 本方法只删自己那八张表, 并把删掉的 conversationId
 * <b>返回给调用方</b>, 让对面拿这份清单去清它自己的那张 {@code session_summaries}
 * (它按 conversationId 存, 而 {@code phone_notifications} / {@code pending_message_states}
 * 本来就带 companionId, 那边可以直接按 peer 删)。
 *
 * <h2>为什么是硬删除</h2>
 *
 * 这是用户 2026-09-17 明确选的: 「连消息一起删掉」。备选的"打上结束标记、只是不再显示"
 * 被否掉了 —— 他要的是它真的消失, 不是一个不显示的墓碑。代价是这段历史不可恢复, 所以
 * 调用点必须是一次**显式**的删除动作({@code DELETE /api/companions/{id}}), 而不是任何
 * 顺带发生的清理。
 *
 * <p>方法刻意做成幂等的: peer 没有会话、或已经被清过, 都返回空列表而不是抛异常 ——
 * 删两次的第二次不该失败。
 */
@Slf4j
@Service
public class ConversationPurgeService {

    private final ConversationRepository convRepo;
    private final MessageRepository msgRepo;
    private final ConversationParticipantRepository participantRepo;
    private final ConversationReadStateRepository readStateRepo;
    private final ConversationExchangeRepository exchangeRepo;
    private final ConversationBoundaryRepository boundaryRepo;
    private final ConversationThreadRepository threadRepo;
    private final ConversationSessionRepository sessionRepo;

    public ConversationPurgeService(ConversationRepository convRepo, MessageRepository msgRepo,
                                    ConversationParticipantRepository participantRepo,
                                    ConversationReadStateRepository readStateRepo,
                                    ConversationExchangeRepository exchangeRepo,
                                    ConversationBoundaryRepository boundaryRepo,
                                    ConversationThreadRepository threadRepo,
                                    ConversationSessionRepository sessionRepo) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.participantRepo = participantRepo;
        this.readStateRepo = readStateRepo;
        this.exchangeRepo = exchangeRepo;
        this.boundaryRepo = boundaryRepo;
        this.threadRepo = threadRepo;
        this.sessionRepo = sessionRepo;
    }

    /**
     * 删掉这个 peer 在本仓的一切, 返回被删掉的 conversationId 清单。
     *
     * <p>返回清单不只是"顺手报告一下": 调用方(仿真 Agent 平台)有一张按 conversationId
     * 存的 {@code session_summaries}, 而它自己算不出这些 id —— 见类注释。给清单比让它
     * 反向读本仓的表要干净得多。
     */
    @Transactional
    public List<String> purgePeer(String companionId) {
        List<String> ids = convRepo.findByCompanionIdOrderByLastMessageAtDesc(companionId).stream()
                .map(Conversation::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        // 先子后父。这些表之间没有数据库外键(Hibernate 的 ddl-auto 只加列, 不建约束),
        // 所以顺序不影响成败 —— 但保持"先子后父"是因为它**将来**会重要: 一旦补上外键,
        // 反过来删会立刻撞约束。现在就按那个顺序写, 补外键那天不用回来改这里。
        long messages = msgRepo.deleteByConversationIdIn(ids);
        participantRepo.deleteByConversationIdIn(ids);
        readStateRepo.deleteByConversationIdIn(ids);
        exchangeRepo.deleteByConversationIdIn(ids);
        threadRepo.deleteByConversationIdIn(ids);
        boundaryRepo.deleteByConversationIdIn(ids);
        sessionRepo.deleteByConversationIdIn(ids);
        convRepo.deleteAllByIdInBatch(ids);

        log.info("[Purge] peer {} 的 {} 段会话已连同 {} 条消息一起删除",
                companionId, ids.size(), messages);
        return ids;
    }

    /** 「这个 peer 还有没有会话」—— 给只想知道影响面的调用方, 不做任何写入。 */
    @Transactional(readOnly = true)
    public long conversationCountOf(String companionId) {
        return convRepo.countByCompanionId(companionId);
    }
}
