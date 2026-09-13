package com.luxera.companion.contracts.chat;

import com.luxera.companion.contracts.application.PrincipalType;

/**
 * LAP v2 §64: <b>这一场里的一个人</b>。
 *
 * <p>叫 {@code ParticipantView} 而不是 {@code MemberView}, 是 §130 原则 4 的直接后果:
 * 真人、本平台的数字人、外部的 Agent 在这里是同一张表里的同一行, 区别只在
 * {@link #principalType()}。所以聊天界面画这张列表时不需要问"哪些是人、哪些是机器人" ——
 * 它要问的是"谁在这一场里", 而那是一个与类型无关的问题。
 *
 * <p><b>{@code owner} 单独给出, 而不是让调用方去比 {@code role == "OWNER"}。</b>
 * 那是同一个判断写两遍, 而第二遍迟早会被写成 {@code equalsIgnoreCase} 或者漏掉一处。
 * 权柄的判据在平台里只有一条({@code SessionParticipantRecord#owner()}), 这里只是把它
 * 搬过来 —— 不是重新实现一遍。
 *
 * @param participantId 参与者<em>行</em>的 id(§16 原文如此), 不是 principal id ——
 *                      同一个人在两段对话里是两行, 拿会话内的 id 引用他才是对的粒度
 * @param principalType 这一位是谁: HUMAN / AGENT / EXTERNAL_AGENT / SYSTEM / APPLICATION
 * @param principalId   对应类型的 id(userId 或 companionId)
 * @param role          OWNER / MEMBER / OBSERVER —— 决定默认能力集
 * @param status        ACTIVE / LEFT / REMOVED
 * @param owner         是不是这一场的主人(权柄, 不是出处)
 */
public record ParticipantView(
        String participantId,
        PrincipalType principalType,
        String principalId,
        String role,
        String status,
        boolean owner
) {

    /** 还在场吗 —— 退场的人和从没来过的人在这里被分开: 前者读得到历史, 后者什么都看不到。 */
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
