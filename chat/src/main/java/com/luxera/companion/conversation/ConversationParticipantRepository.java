package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationParticipantRepository extends JpaRepository<ConversationParticipant, String> {
    List<ConversationParticipant> findByConversationId(String conversationId);
    boolean existsByConversationIdAndMemberId(String conversationId, String memberId);
}
