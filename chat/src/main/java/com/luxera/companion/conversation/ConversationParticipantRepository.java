package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationParticipantRepository extends JpaRepository<ConversationParticipant, String> {
    List<ConversationParticipant> findByConversationId(String conversationId);
    boolean existsByConversationIdAndMemberId(String conversationId, String memberId);

    /**
     * 「我参与的会话」。这是列表页唯一正确的入口 —— **不是** {@code Conversation.userId}。
     *
     * <p>一对一里两者结果相同(每次建会话都会把 user 注册成参与者), 所以这里看不出区别。
     * 群聊里区别是本质的: 会话的 {@code userId} 只是**创建者**, 而"我的会话"应该是
     * "我在参与者的名单里"。一期就走对的那条路, 二期加群聊时不用回来重写查询。
     *
     * <p>{@code LeftAtIsNull} 是退群 —— 退了的会话不该再出现在列表里, 但行要留着
     * (消息里的 {@code senderId} 还要靠它显示成"某某"而不是一串 id)。
     */
    List<ConversationParticipant> findByMemberIdAndLeftAtIsNull(String memberId);

    /** 一批会话的全部参与者 —— 列表页一次取回, 不在循环里查 */
    List<ConversationParticipant> findByConversationIdIn(java.util.Collection<String> conversationIds);
}
