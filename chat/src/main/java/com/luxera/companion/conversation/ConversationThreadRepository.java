package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationThreadRepository extends JpaRepository<ConversationThread, String> {

    List<ConversationThread> findByCompanionIdAndStatusOrderByLastMessageAtDesc(String companionId, String status);

    List<ConversationThread> findByCompanionIdOrderByLastMessageAtDesc(String companionId);

    List<ConversationThread> findByConversationIdOrderByLastMessageAtDesc(String conversationId);

    Optional<ConversationThread> findFirstByConversationIdAndStatusOrderByLastMessageAtDesc(
            String conversationId, String status);

    /** 级联清理用, 见 {@link ConversationPurgeService} */
    long deleteByConversationIdIn(java.util.Collection<String> conversationIds);
}
