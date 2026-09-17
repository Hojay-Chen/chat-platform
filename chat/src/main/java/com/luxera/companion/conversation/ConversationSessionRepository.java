package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {
    Optional<ConversationSession> findTopByConversationIdOrderByStartedAtDesc(String conversationId);
    List<ConversationSession> findByConversationIdOrderByStartedAtDesc(String conversationId);

    /** 级联清理用, 见 {@link ConversationPurgeService} */
    long deleteByConversationIdIn(java.util.Collection<String> conversationIds);
}
