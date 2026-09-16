package com.luxera.companion.conversation;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 「会话列表里的一行」—— 前端聊天 tab 那一屏要的全部信息, 一次给全。
 *
 * <h2>为什么不是 {@code ConversationView} 加几个字段</h2>
 *
 * {@code ConversationView} 是**给数字人平台看的**: 它关心 userId / messageCount / summary
 * 这些"这个会话是什么"的事实。列表页关心的是另一组东西 —— 对方是谁、最后说了什么、
 * 我读没读、我置顶了没有。后者里有三项({@code unreadCount} / {@code pinned} / {@code muted})
 * 属于「我和这个会话的关系」, 不是会话自己的属性, 塞进 {@code ConversationView} 会让
 * 那个类型同时承担两种视角。
 *
 * <p>这不是在契约仓里加类型, 所以它只影响 chat 自己的 HTTP 面。
 */
@Getter
@Builder
public class ConversationSummaryView {

    private final String id;

    /** 对方(peer)的不透明 id —— 一期是 companionId。前端的 {@code PeerRef.id} 就是它 */
    private final String peerId;

    /** 对方显示名。取自参与者行(role=agent), 取不到就退回会话标题 —— 宁可有, 不可空 */
    private final String peerName;

    private final String title;
    private final String status;
    private final int messageCount;
    private final LocalDateTime lastMessageAt;

    /** 最后一条消息的摘要。从没说过话的会话这里是 null */
    private final LastMessage lastMessage;

    private final int unreadCount;
    private final boolean pinned;
    private final boolean muted;

    /**
     * 最后一条消息的**摘要**, 不是消息本身。
     *
     * <p>列表只需要四个字段, 而一条完整消息带着 metadata / exchangeId / sessionId ——
     * 一个二十会话的列表会因此多传几十 KB 的没人看的东西。
     */
    @Getter
    @Builder
    public static class LastMessage {
        private final String id;
        /** {@code user | companion | system} */
        private final String senderType;
        private final String senderId;
        private final String content;
        private final String messageKind;
        private final LocalDateTime createdAt;
    }
}
