package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    List<Conversation> findByUserIdAndCompanionIdOrderByLastMessageAtDesc(String userId, String companionId);

    /**
     * 一个用户的全部会话。**不是列表页的入口** —— 列表页走参与者表, 见
     * {@code ConversationService#listForMember}。这个方法留在那里是给那条并集兜底用的。
     */
    List<Conversation> findByUserIdOrderByLastMessageAtDesc(String userId);
    List<Conversation> findByCompanionIdOrderByLastMessageAtDesc(String companionId);
    long countByCompanionId(String companionId);

    /** Every peer chat has ever held a conversation with — the maintenance job's work list. */
    @Query("select distinct c.companionId from Conversation c")
    List<String> findDistinctCompanionIds();
}
