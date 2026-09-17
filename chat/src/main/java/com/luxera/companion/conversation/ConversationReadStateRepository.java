package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationReadStateRepository extends JpaRepository<ConversationReadState, String> {

    Optional<ConversationReadState> findByConversationIdAndMemberId(String conversationId, String memberId);

    /** 一次把我所有会话的读状态取回来 —— 会话列表页要的就是这个, 不要一个个查 */
    List<ConversationReadState> findByMemberId(String memberId);

    /** 级联清理用, 见 {@link ConversationPurgeService} */
    long deleteByConversationIdIn(java.util.Collection<String> conversationIds);
}
