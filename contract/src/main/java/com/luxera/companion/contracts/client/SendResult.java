package com.luxera.companion.contracts.client;

import java.time.LocalDateTime;

/**
 * V2.2 §6.3 第 6 项 —— "我说完了"的回执。
 *
 * <p>刻意只有两个字段: 消息 id 与时刻。真人在微信里按下发送之后拿到的是什么? 气泡出现在
 * 自己这一侧、带一个时间。他拿不到"对方看见了没有"(那要等回执, 是另一件事, 由
 * {@code deliveryStatus} 与消息流回答)。
 *
 * <p>回消息 id 是必须的: 调用方要用它去对应本地那条乐观消息(与平台既有的
 * {@code clientMessageId} 幂等键是同一对概念, 一个管"别发两遍", 一个管"哪条是我刚发的")。
 *
 * @param messageId 落库后的规范化消息 id
 * @param sentAt    落库时刻
 */
public record SendResult(
        String messageId,
        LocalDateTime sentAt
) {
}
