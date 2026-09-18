package com.luxera.companion.contracts.client;

import java.time.LocalDateTime;

/**
 * V2.2 §6.3 第 3 项 —— 「聊天列表里的一行」, 也就是**真人打开微信看到的那一屏**。
 *
 * <h2>它为什么是"账号"而不是"会话"</h2>
 *
 * <p>真人不会说"我要打开会话 {@code conv-9f3a}", 他说的是"我要找小满说话"。所以这一行的
 * 主键是{@link #accountId() 对方的聊天账号 id}, 而 {@link #conversationId()} 只是平台内部
 * 的关联字段(客户端拿它把 {@link NotificationSignal} 与某一行对上, 不拿它寻址)。
 *
 * <h2>为什么它<em>没有</em>最后一条消息的预览</h2>
 *
 * <p>因为真实聊天软件的列表页显示的预览, 是<em>客户端自己缓存过的正文</em>拼出来的 ——
 * 微信并没有在"列表"这个接口里回正文。V2.2 更要紧的一层是 §8.2.4: <b>正文只能从"读消息"
 * 那个接口出来</b>。列表里带上预览, 等于给正文开了第二条路, 而那条路会让"她还没读就知道
 * 内容"变成一个合法状态。
 *
 * @param conversationId 平台内部的会话 id(关联用, 不是寻址用)
 * @param accountId      对方在这个聊天软件里的账号 id
 * @param unreadCount    我在这段会话里的未读条数。<b>免打扰不影响它</b> —— 免打扰只是不响铃,
 *                       红点照旧(§6.1: "未读计数仍然 +1")。
 * @param lastActivityAt 这段会话最后有动静的时刻(没说过话时为 null)
 * @param pinned         我置顶了没有
 * @param muted          我免打扰了没有。<b>这是平台侧的那个开关</b>(§6.2 上表): 它为真时,
 *                       平台一条 {@link NotificationSignal} 都不发。
 */
public record ClientConversation(
        String conversationId,
        String accountId,
        int unreadCount,
        LocalDateTime lastActivityAt,
        boolean pinned,
        boolean muted
) {
}
