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

    /**
     * 一批会话的全部消息 —— 只给 {@link ConversationPurgeService} 用。
     *
     * <p>派生删除**不需要** {@code @Modifying}(那是给带 {@code @Query} 的删除用的);
     * 它绕过持久化上下文直接下发一条 DELETE, 对"整段会话都不要了"正是想要的语义。
     */
    long deleteByConversationIdIn(java.util.Collection<String> conversationIds);

    /** 幂等键查询(clientMessageId 同会话唯一) */
    Optional<Message> findByConversationIdAndClientMessageId(String conversationId, String clientMessageId);

    /**
     * 一批会话各自的最新一条消息 —— 会话列表页的"最后说了什么"。
     *
     * <p>放一条查询里而不是每个会话查一次, 是因为列表页的会话数会一直涨, 而
     * "循环里查一次"这种写法一旦写下去就很难被注意到 —— 它在三个会话时看不出慢。
     *
     * <p>用 {@code max(createdAt)} 而不是 {@code max(id)}: id 是随机 UUID, 大小顺序
     * 与时间顺序无关。同一毫秒内的两条会都被取出来, 调用方取先遇到的那条即可。
     */
    @Query("select m from Message m where m.conversationId in :ids "
            + "and m.createdAt = (select max(x.createdAt) from Message x where x.conversationId = m.conversationId)")
    List<Message> findLatestPerConversation(@Param("ids") java.util.Collection<String> ids);

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
