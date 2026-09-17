package com.luxera.companion.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §五十二: 会话参与者服务。
 * 创建会话时自动加入 Agent + User 两个参与者(一对一聊天即最小图)。
 *
 * <p>Chat-platform side: participants are identified by an <em>opaque member id</em>. For the
 * agent side that is whatever peer id the digital-human platform handed over via
 * {@code CompanionDirectoryPort.CompanionRef#peerMemberId()} — chat stores it and never resolves
 * it against a persona table, because there is no persona table on this side.
 */
@Service
public class ConversationParticipantService {

    private final ConversationParticipantRepository repo;

    public ConversationParticipantService(ConversationParticipantRepository repo) {
        this.repo = repo;
    }

    /** 会话创建后调用: 注册 Agent 与 User 参与者(幂等) */
    @Transactional
    public void seed(Conversation conv, String userId, String memberId, String memberDisplayName) {
        addIfMissing(conv.getId(), memberId, ConversationParticipant.ROLE_AGENT, memberDisplayName);
        addIfMissing(conv.getId(), userId, ConversationParticipant.ROLE_USER, null);
    }

    @Transactional
    public void addIfMissing(String conversationId, String memberId, String role, String displayName) {
        if (repo.existsByConversationIdAndMemberId(conversationId, memberId)) return;
        ConversationParticipant p = new ConversationParticipant();
        p.setConversationId(conversationId);
        p.setMemberId(memberId);
        p.setRole(role);
        p.setDisplayName(displayName);
        repo.save(p);
    }

    /**
     * 把 agent 参与者那一行的 {@code member_id} 从旧值**就地**改成新值。
     *
     * <h2>为什么是"改"而不是"删掉再加"</h2>
     *
     * 因为参与者行不只是一条归属记录 —— {@code joined_at} 是"这个人什么时候进的这段会话",
     * 而消息里存的 {@code sender_id} 要靠它才能显示成名字。删了再加会把加入时间重置成现在,
     * 一个两年前的会话里出现一个"今天刚加入"的参与者。
     *
     * <p>另外, 删除 + 插入在两个事务之间会留下一个"谁都不在里面"的瞬间, 而这是生产库上的
     * 一次在线迁移, 不是离线批处理。
     *
     * <h2>两个"不改"的情况</h2>
     *
     * <ul>
     *   <li><b>没有那一行</b> —— 调用方要改的那一行不存在(会话还没有参与者, 或者它指的
     *       根本不是这个 id)。返回 false, 不补一行: 补出来的行没有 {@code displayName},
     *       会覆盖掉列表页上正确的名字。</li>
     *   <li><b>目标 id 已经有一行</b> —— {@code uk_conv_participant} 是
     *       {@code (conversation_id, member_id)} 上的唯一约束, 改过去会直接撞它。这种并存
     *       本身说明数据被改过至少一次, 该由人去判断留哪一行, 而不是让一次迁移替它决定。</li>
     * </ul>
     *
     * @return true = 改了一行; false = 什么都没做(上述两种情况之一)
     */
    @Transactional
    public boolean repointAgent(String conversationId, String fromMemberId, String toMemberId) {
        if (fromMemberId == null || toMemberId == null || fromMemberId.equals(toMemberId)) return false;
        if (repo.existsByConversationIdAndMemberId(conversationId, toMemberId)) return false;
        for (ConversationParticipant p : repo.findByConversationId(conversationId)) {
            if (!ConversationParticipant.ROLE_AGENT.equals(p.getRole())) continue;
            if (!fromMemberId.equals(p.getMemberId())) continue;
            p.setMemberId(toMemberId);
            repo.save(p);
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public java.util.List<ConversationParticipant> participants(String conversationId) {
        return repo.findByConversationId(conversationId);
    }
}
