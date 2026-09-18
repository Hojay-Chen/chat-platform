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
     * 一段会话里某一方发的全部消息 —— 今天只有"把真人那侧的消息标成已读"用它。
     *
     * <p>刻意不在查询里再叠一个 {@code deliveryStatus <> 'READ'}: 那一列是**可空**的
     * (加列与历史数据), 而 SQL 里 {@code NULL <> 'READ'} 是 NULL 不是 true ——
     * 老行会被悄悄漏掉, 表现为"标了已读但有些消息还是未读", 只看查询语句看不出来。
     * 状态过滤放在 java 里, null 就按"还没读过"算。
     */
    List<Message> findByConversationIdAndSenderType(String conversationId, String senderType);

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

    /**
     * V2.2 §6.3 第 4/5 项 —— 「先给一屏, 想往上翻再拉」那一页。
     *
     * <h2>为什么游标是 (createdAt, id) 两个字段, 而不是一个 messageId</h2>
     *
     * <p>按 messageId 分页要求 id 有序, 而本平台的 id 是随机 UUID —— 大小顺序与时间顺序
     * 无关, 拿它当游标会得到一页随机消息。按 createdAt 单独分页则会在同一毫秒内的两条上
     * 出错: 游标那一毫秒里的另一条会被<em>跳过</em>, 表现为"有一条消息怎么翻都看不到"。
     * 这也是 {@code ConversationReadState.lastReadMessageId} 当初不用时间戳的同一个理由。
     *
     * <p>所以游标是这两列的组合, 判据写成 {@code createdAt < t OR (createdAt = t AND id < i)}
     * —— 一行 SQL 里把"更早的"与"同一时刻里更靠前的"一起覆盖掉。
     *
     * <p><b>第一页怎么走这条路</b>: 调用方传一个远期时刻({@code 9999-12-31T23:59})与空字符串。
     * 不写两个方法、不用 {@code :param is null} —— 后者在 JPQL 里要靠驱动去猜参数类型,
     * 而"猜不出来"的表现是一次运行时的参数绑定异常, 只在真的翻到第二页时才出现。
     */
    @Query("select m from Message m where m.conversationId = :conversationId "
            + "and (m.createdAt < :beforeAt or (m.createdAt = :beforeAt and m.id < :beforeId)) "
            + "order by m.createdAt desc, m.id desc")
    List<Message> pageBefore(@Param("conversationId") String conversationId,
                             @Param("beforeAt") LocalDateTime beforeAt,
                             @Param("beforeId") String beforeId,
                             org.springframework.data.domain.Pageable pageable);

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
