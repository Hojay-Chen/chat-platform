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
 * <p>本 record 的五个分量全部是<em>标识符、序号、计数与时刻</em>, 它们的取值来自**封闭的命名空间**
 * (会话 id / 聊天账号 id / 平台自增序号 / 自然数计数 / 时间戳)。类型里没有任何一个字段能承载一段自由文本
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
 * 下面五个名字 —— 以后任何人往这里加一个 {@code preview} 字段, 测试立刻变红。
 * <b>1.0.2 加 {@code unreadCount} 时这条用例确实红了</b>, 而那正是它的用法: 红一次,
 * 回答一次"它是内容吗"(不是, 它是一个计数), 然后把期望值改成五个。
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
 * @param unreadCount    <b>这个会话此刻的未读数</b> —— 接收方在红点上看到的那个数字。
 *                       它是本类型上<b>唯一一个不是标识符的分量</b>, 所以要先回答
 *                       {@code NotificationSignalShapeTest} 那句质问: <b>它是内容吗?</b>
 *                       不是。它是一个 int, 取值范围是自然数, 与"说了什么"没有关系 ——
 *                       与 {@code preview} / {@code senderName} 那一类字段的区别是:
 *                       那两类字段的值域是**开放的自由文本**, 而自由文本就是内容的载体。
 *                       一个计数没有可承载正文的地方。
 *
 *                       <h3>为什么它是 {@code Integer} 而不是 {@code int}</h3>
 *                       <p>为了让<b>生产方太旧</b>这件事变成一个响亮的失败, 而不是一个安静的
 *                       0。{@code int} 的默认反序列化结果是 0, 而 0 是一个完全合理的未读数
 *                       ("这个会话没有未读") —— 于是一个没有发出这个字段的旧服务, 会让所有
 *                       客户端的红点永远不亮, 而现象是"她什么都没收到", 不是任何一条报错。
 *                       这种情况在本项目的部署方式下是真实存在的: 服务是就地覆盖 fat jar 后
 *                       重启的, 库里的 artifact 版本与实际在跑的进程可以不一致。
 *                       <b>null 的语义因此被定成"对面没给", 而不是"未读为零"</b> ——
 *                       消费方应当在 null 上抱怨(见仓 2 的 {@code HttpChatPlatformGateway}),
 *                       不要拿 0 顶上。
 *
 *                       <h3>平台算得出它, 而且不算就是不给</h3>
 *                       <p>产生信号的时刻正是消息提交之后({@code ClientNotificationService}
 *                       的 AFTER_COMMIT 监听), 而那一刻 {@code ConversationReadState} 里的
 *                       {@code unreadCount} 刚被 {@code bumpOnMessage} 加过 1。
 *                       所以这个值不是估算、不是客户端自己累加的, 是<b>平台读自己刚提交的
 *                       那一行</b>得来的。这也是它必须由平台给的理由: 客户端自己数的话,
 *                       免打扰拦掉的会话、超过补发容量的信号、以及"她离线期间平台少发的那些"
 *                       都会让它慢慢漂走, 而漂走的未读数看起来与正确的一模一样。
 *
 *                       <h3>补发时它是什么</h3>
 *                       <p>补发的历史信号带着<b>它产生当时</b>的未读数, 不是补发当时的。
 *                       这不是缺陷: 补发的用途是让客户端把"错过的那几声响"补上, 而每一声
 *                       当时对应的红点值就是它。客户端拿到一批补发信号后要显示当前未读,
 *                       应当以 {@code GET /api/client/conversations} 为准(那是列表页本来
 *                       就要拉的), 而不是取补发序列里的最后一个。
 */
public record NotificationSignal(
        long signalId,
        String conversationId,
        String fromAccountId,
        java.time.LocalDateTime raisedAt,
        Integer unreadCount
) {
}
