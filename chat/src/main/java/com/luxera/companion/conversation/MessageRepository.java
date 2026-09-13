package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, String> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    List<Message> findTop200ByConversationIdOrderByCreatedAtDesc(String conversationId);
    long countByConversationId(String conversationId);

    /** 幂等键查询(clientMessageId 同会话唯一) */
    Optional<Message> findByConversationIdAndClientMessageId(String conversationId, String clientMessageId);

    @Query("select m from Message m where m.senderType = 'user' and m.createdAt >= :since "
            + "and m.conversationId in (select c.id from Conversation c where c.companionId = :companionId)")
    List<Message> findUserMessagesSince(@Param("companionId") String companionId,
                                        @Param("since") LocalDateTime since);

    @Query("select m from Message m where m.createdAt >= :since and m.createdAt < :until "
            + "and m.conversationId in (select c.id from Conversation c where c.companionId = :companionId) "
            + "order by m.createdAt asc")
    List<Message> findMessagesBetween(@Param("companionId") String companionId,
                                      @Param("since") LocalDateTime since,
                                      @Param("until") LocalDateTime until);

    // ── 主动消息 = Chat 消息(kind=PROACTIVE), 用于去重/间隔 bookkeeping ──
    @Query("select m from Message m where m.messageKind = :kind "
            + "and m.conversationId in (select c.id from Conversation c where c.companionId = :companionId) "
            + "order by m.createdAt desc")
    List<Message> findRecentByCompanionIdAndMessageKind(
            @Param("companionId") String companionId, @Param("kind") String kind,
            org.springframework.data.domain.Pageable pageable);

    @Query("select count(m) from Message m where m.messageKind = :kind "
            + "and m.conversationId in (select c.id from Conversation c where c.companionId = :companionId) "
            + "and m.createdAt >= :since")
    long countByCompanionIdAndMessageKindAndCreatedAtAfter(
            @Param("companionId") String companionId, @Param("kind") String kind,
            @Param("since") LocalDateTime since);
}
