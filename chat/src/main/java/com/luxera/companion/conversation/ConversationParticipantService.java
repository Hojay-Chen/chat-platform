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

    @Transactional(readOnly = true)
    public java.util.List<ConversationParticipant> participants(String conversationId) {
        return repo.findByConversationId(conversationId);
    }
}
