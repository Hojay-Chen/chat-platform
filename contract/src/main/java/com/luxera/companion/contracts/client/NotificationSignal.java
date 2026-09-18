package com.luxera.companion.contracts.client;

/**
 * V2.2 §6.1/§6.3 —— <b>一条消息一条的「通知信号」，它不携带任何消息内容。</b>
 *
 * <h2>它是什么</h2>
 *
 * <p>现实世界里"手机响了一下"。它不是消息, 不是消息摘要, 也不是"你有 3 条新消息"。它就是
 * 一声铃: <b>哪一段会话有人说话了、是谁说的、什么时候说的</b>。要看到说了什么, 客户端必须
 * 再发一次 HTTP 请求({@code GET /api/client/conversations/{accountId}/messages})去把它读出来
 * —— 这正是真人打开微信才能看到内容的那个动作。
 *
 * <h2>「不带内容」是怎么在类型上保证的, 而不是靠一句约定</h2>
 *
 * <p>本 record 的四个分量全部是<em>标识符、序号与时刻</em>, 它们的取值来自**封闭的命名空间**
 * (会话 id / 聊天账号 id / 平台自增序号 / 时间戳)。类型里没有任何一个字段能承载一段自由文本
 * —— 没有 {@code content}, 没有 {@code preview}, 没有 {@code senderName}。
 * 于是"往通知里塞正文"这件事在**编译期**就不成立: 想塞也没有地方塞。
 *
 * <p>这条性质与 {@code PhoneNotificationPayload}(V10 的旧通知载荷, 带 {@code preview} 与
 * {@code privacyMode}) 是<em>刻意不同</em>的两个东西, 而不是同一件事的两版实现。那个类型的
 * {@code privacyMode} 是**调用方给的**(FULL_PREVIEW / SENDER_ONLY / NO_PREVIEW), 也就是说
 * "要不要在通知里显示正文"这个决定权在调用方手里 —— 只要有人传 FULL_PREVIEW, 正文就会
 * 顺着通知流出去, 而平台没有任何一条代码能阻止它。V2.2 §6.1/§8.2.4 要的恰恰相反:
 * <b>正文只能从"读消息"那个接口出来</b>, 平台不给第二条路。所以这里没有复用那个类型,
 * 而是新开一个没有可泄漏字段的类型。
 *
 * <p>{@code NotificationSignalShapeTest} 用反射把这条性质钉住: 它断言 record 的分量恰好是
 * 下面四个名字 —— 以后任何人往这里加一个 {@code preview} 字段, 测试立刻变红。
 *
 * <h2>为什么字段名都带 {@code ...Id} / {@code ...At} 后缀</h2>
 *
 * <p>因为它们**只能**是 id 和时刻。一个叫 {@code from} 的 String 字段读的人会以为它能放
 * 任何东西; 一个叫 {@code conversationId} 的字段, 读的人知道它必须来自会话 id 的命名空间。
 * 命名在这里是安全措施的一部分, 不是风格偏好。
 *
 * @param signalId       <b>本账号内单调递增的序号</b>, 从 1 开始。它同时是断线重连的游标
 *                       (§6.6: 客户端上报 {@code lastAckSignalId}, 服务端只补发它之后的那几条)。
 *                       刻意不用 UUID: 它要能比较大小, 否则"补发哪些"就得再看一遍时间戳,
 *                       而同一毫秒里的两条会因此互相盖掉。
 * @param conversationId 哪一段会话。**平台内部 id**, 不代表任何内容 —— 它只是让客户端能把
 *                       信号与列表里的某一行对上。
 * @param fromAccountId  谁发的, 聊天账号 id(不是姓名, 不是备注)。"这个人是谁"是客户端自己的
 *                       知识(§6.3 第 9 项), 平台只给 id。
 * @param raisedAt       信号产生的时刻。
 *                       <p><b>类型口径</b>: 这里用 {@code LocalDateTime}(平台时区 Asia/Shanghai)
 *                       而不是设计文档草稿里的 {@code Instant} —— 本平台所有对外 HTTP 响应
 *                       ({@code MessageView.createdAt} / {@code ConversationView.lastMessageAt})
 *                       都是这个口径, 而这一条会和它们出现在同一个客户端里。契约里混用两种
 *                       时间类型, 只会让对面多写一个转换函数。
 */
public record NotificationSignal(
        long signalId,
        String conversationId,
        String fromAccountId,
        java.time.LocalDateTime raisedAt
) {
}
