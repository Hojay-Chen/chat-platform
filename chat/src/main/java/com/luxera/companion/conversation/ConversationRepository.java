package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    List<Conversation> findByUserIdAndCompanionIdOrderByLastMessageAtDesc(String userId, String companionId);
    List<Conversation> findByCompanionIdOrderByLastMessageAtDesc(String companionId);
    long countByCompanionId(String companionId);

    /** Every peer chat has ever held a conversation with — the maintenance job's work list. */
    @Query("select distinct c.companionId from Conversation c")
    List<String> findDistinctCompanionIds();
}
