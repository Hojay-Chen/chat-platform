package com.luxera.companion.contracts.client;

import java.time.LocalDateTime;

/**
 * V2.2 §6.3 第 4/5 项 —— 一条消息, <b>平台里唯一携带正文的形状</b>。
 *
 * <h2>「正文只有一个出口」是怎么成立的</h2>
 *
 * <p>整个 {@code contracts.client} 包里, 只有本类型有 {@link #content()}。{@link NotificationSignal}
 * 没有, {@link ClientConversation} 没有, {@link ContactProfile} 没有。于是"agent 必须先做一次
 * '读消息'的行为才能知道内容"这条设计(§8.2.4 验收标准 E)不是一条纪律, 而是一条类型上的事实:
 * 想去读内容, 就只能调到那个返回本类型的端点。
 *
 * <p>这一点在跨仓时尤其重要 —— 仓 2 的 {@code Mind.workingMemory} 里出现正文的唯一途径,
 * 就是它自己发起了 {@code readMessages}。
 *
 * @param messageId       消息 id
 * @param senderAccountId 谁发的 —— **聊天账号 id**, 不是姓名, 也不是备注。"这个人是谁"是
 *                        客户端自己的知识(§6.3 第 9 项)。
 * @param kind            TEXT / IMAGE / SYSTEM / APPLICATION_CARD …(平台的消息种类原值, 不翻译)
 * @param content         正文
 * @param sentAt          发送时刻
 * @param deliveryStatus  PENDING / DELIVERED / READ / DEFERRED / IGNORED
 */
public record ClientMessage(
        String messageId,
        String senderAccountId,
        String kind,
        String content,
        LocalDateTime sentAt,
        String deliveryStatus
) {
}
