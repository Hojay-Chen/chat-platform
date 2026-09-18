package com.luxera.companion.contracts.client;

/**
 * V2.2 §6.6 —— {@code WS /api/client/stream} 上两种方向的帧。
 *
 * <p>长连接上跑的**只有元信息**: 服务端 → 客户端是 {@link #TYPE_NOTIFICATION}(不含正文)、
 * {@link #TYPE_READY}、{@link #TYPE_SESSION_EXPIRED}; 客户端 → 服务端是
 * {@link #TYPE_PING} 与 {@link #TYPE_ACK}。没有任何一种帧能承载消息正文 —— 因为载荷里那个
 * 唯一的"有内容的"字段是 {@link NotificationSignal}, 而它本身没有可承载正文的地方
 * (见那个类型的说明)。这条性质让"通知"与"正文"在<em>协议层</em>就是两条路, 而不是靠
 * 客户端自觉不去读某个字段。
 *
 * <h2>为什么状态帧与通知帧是同一个类型</h2>
 *
 * <p>客户端只需要一个解析分支。分成两个 JSON 形状("这条是通知""那条是状态")会让每一种
 * 客户端都要先看它在不在对的那条路上 —— 而它们共用同一条连接, 顺序是重要的
 * ({@code READY} 必须在那批补发的 {@code NOTIFICATION} 之前)。
 *
 * @param type   {@link #TYPE_READY} / {@link #TYPE_NOTIFICATION} / {@link #TYPE_PONG} /
 *               {@link #TYPE_SESSION_EXPIRED} / {@link #TYPE_ERROR}
 * @param signal 只有 {@link #TYPE_NOTIFICATION} 有值
 * @param reason 只有 {@link #TYPE_ERROR} / {@link #TYPE_SESSION_EXPIRED} 有值 —— 一句人话,
 *               <b>永远不含消息正文</b>(它是协议层面的解释, 与聊天内容无关)
 * @param lastAckSignalId 客户端 → 服务端: {@link #TYPE_ACK} 里"我确认收到这个序号为止"。
 *               服务端据此知道补发到哪里为止。为 0 表示"还没有确认过任何一条"。
 */
public record ClientStreamFrame(
        String type,
        NotificationSignal signal,
        String reason,
        long lastAckSignalId
) {

    /** 服务端 → 客户端: 连接已就绪(且**在同一次握手里就完成了**身份与补发判定)。 */
    public static final String TYPE_READY = "READY";
    /** 服务端 → 客户端: 一条通知信号。不含正文。 */
    public static final String TYPE_NOTIFICATION = "NOTIFICATION";
    /** 服务端 → 客户端: 对客户端 PING 的回应。 */
    public static final String TYPE_PONG = "PONG";
    /** 服务端 → 客户端: 这个会话令牌已经不能用了, 客户端必须重新 login。 */
    public static final String TYPE_SESSION_EXPIRED = "SESSION_EXPIRED";
    /** 双向: 出错(参数不合法 / 帧解析失败)。 */
    public static final String TYPE_ERROR = "ERROR";

    /** 客户端 → 服务端: 心跳。 */
    public static final String TYPE_PING = "PING";
    /** 客户端 → 服务端: "第 N 条及之前的信号我收到了"。 */
    public static final String TYPE_ACK = "ACK";

    public static ClientStreamFrame ready() {
        return new ClientStreamFrame(TYPE_READY, null, null, 0);
    }

    public static ClientStreamFrame notification(NotificationSignal signal) {
        return new ClientStreamFrame(TYPE_NOTIFICATION, signal, null, 0);
    }

    public static ClientStreamFrame pong() {
        return new ClientStreamFrame(TYPE_PONG, null, null, 0);
    }

    public static ClientStreamFrame error(String reason) {
        return new ClientStreamFrame(TYPE_ERROR, null, reason, 0);
    }

    public static ClientStreamFrame sessionExpired(String reason) {
        return new ClientStreamFrame(TYPE_SESSION_EXPIRED, null, reason, 0);
    }
}
