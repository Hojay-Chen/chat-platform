package com.luxera.companion.contracts.client;

import java.time.LocalDateTime;

/**
 * V2.2 §6.2 —— 会话通知设置。 <b>这是聊天平台的状态, 不是 Agent 的状态。</b>
 *
 * <h2>免打扰为什么必须由平台判, 而不是交给 Agent</h2>
 *
 * <p>问题看起来可以两边做, 但两边做会得到一个**无法解释的世界**:
 *
 * <ul>
 *   <li>如果平台无条件发信号、由 Agent 自己决定要不要在意, 那么"我免打扰了这个群"就变成了
 *       Agent 平台上的一个开关 —— 而它是**聊天软件的用户设置**。真人换一个客户端登录,
 *       这个设置应该还在; 而在那个设计里它跟着 Agent 的实现走。</li>
 *   <li>更要紧的是: 信号一旦发出去, 对面就知道"有人说话了"。"免打扰"的语义是<em>连铃都不响</em>,
 *       不是"响了但我不看"。把判定推到下游, 等于免打扰从来没有生效过。</li>
 * </ul>
 *
 * <p>所以判定的位置只有一个: <b>平台在产生信号之前判</b>。免打扰生效时, 平台一条信号都不发;
 * 未读数照旧 +1(她打开聊天软件时能看到红点)。Agent 能读、能改这个设置, 就像真人用微信的
 * "消息免打扰"开关一样。
 *
 * <h2>两层表: 这一个管"发不发", 手机上的那个管"怎么响"</h2>
 *
 * <pre>
 *   ConversationNotificationSetting.muted   聊天平台   决定要不要**发**通知信号
 *   Phone.NotificationPolicy                她的手机   决定收到信号后**怎么响**
 * </pre>
 *
 * <p>两者都不成立时她才真的"没听见", 而这个状态是可解释的 —— 那正是这套分层的目的。
 * 本仓只负责前一层; 后一层在 Agent 平台的 {@code Device} 里。
 *
 * <h2>它和 {@code ConversationReadState} 的关系: 同一份状态, 两个看法</h2>
 *
 * <p>本类型**不是**一张新表, 它是 {@code conversation_read_state} 那一行
 * ({@code UNIQUE(conversation_id, member_id)}) 的对外投影。库里的 {@code muted_until} 用一个
 * 远期时间点({@code 9999-12-31T23:59})表示"一直免打扰", 于是:
 *
 * <pre>
 *   mutedUntil = 9999-12-31T23:59  ⇔  muted = true
 *   mutedUntil 为空 (或已过去)      ⇔  muted = false
 *   pinnedAt 非空                   ⇔  pinned = true
 * </pre>
 *
 * <p>不另开一张 {@code conversation_notification_setting} 表的理由和"为什么读状态不挂在
 * 会话上"是同一条(见 {@code ConversationReadState} 的类注释): 免打扰、置顶、未读是**同一件
 * 事的三个面** —— "我和这个会话的关系"。拆成两张表之后它们会各自演化, 而"置顶且免打扰"
 * 这种组合一旦不一致, 症状是列表排序与响铃行为对不上, 两段代码各自看都是对的。
 *
 * @param conversationId 哪一段会话
 * @param muted          消息免打扰。为真时平台不发通知信号(未读照常累计)
 * @param pinned         置顶
 * @param updatedAt      这行状态的最后修改时刻
 */
public record ConversationNotificationSetting(
        String conversationId,
        boolean muted,
        boolean pinned,
        LocalDateTime updatedAt
) {
}
