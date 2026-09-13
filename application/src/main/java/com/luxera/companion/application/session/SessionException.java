package com.luxera.companion.application.session;

import com.luxera.companion.contracts.application.ActionStatus;

/**
 * 归属链上的拒绝。
 *
 * <p>带 {@link ActionStatus} 而不是只有一个字符串: "不在场"和"参数不合法"对调用方是两件事,
 * 前者该去找人邀请自己, 后者该改请求。把它们都变成 400 会让客户端只能靠猜。
 */
public class SessionException extends RuntimeException {

    private final String code;
    private final ActionStatus status;

    public SessionException(String code, String message) {
        this(code, message, Statuses.forCode(code));
    }

    public SessionException(String code, String message, ActionStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public ActionStatus status() {
        return status;
    }

    /** 归属链上的失败大多是"请求本身有问题"; 不在场/没资格是明确的拒绝。 */
    private static final class Statuses {
        private Statuses() {
        }

        static ActionStatus forCode(String code) {
            if (code == null) {
                return ActionStatus.INVALID_ARGUMENT;
            }
            return switch (code) {
                // 拒绝: 调用方是"谁"就不对, 改请求没用, 得先改变自己在会话里的位置。
                case "NOT_A_PARTICIPANT", "PARTICIPANT_INACTIVE", "NOT_SESSION_OWNER",
                     "SESSION_INVITE_ONLY", "SESSION_JOIN_CLOSED", "SESSION_FULL" ->
                        ActionStatus.DENIED;
                case "UNKNOWN_APPLICATION", "VERSION_NOT_PUBLISHED", "UNKNOWN_SESSION",
                     "UNKNOWN_INVITATION",
                     // R14 §Developer API: 查无此人也在这张表里 —— 它落在 default 上时是 400,
                     // 而 400 在对调用方说"你的载荷写错了": 于是开发者门户会去改请求体,
                     // 而不是意识到自己拿的是一个过期的 developerId。控制器当时的注释
                     // 写的是 404, 实现却回了 400 —— 差的就是这一行。
                     "UNKNOWN_DEVELOPER" ->
                        ActionStatus.NOT_FOUND;
                // 不是"你的请求写错了"(400 会让客户端去改载荷), 而是"目标的状态不允许这件事了" ——
                // 会话结束该另开一个, 会话没进入 ACTIVE 该等一会儿。两者都是换时机, 不是换载荷。
                case "SESSION_ENDED", "SESSION_NOT_ACTIVE" -> ActionStatus.STATE_CONFLICT;
                // 应用还在, 只是此刻不允许开新会话(§4.1: SUSPENDED / DEPRECATED / 未上架)。
                // 404 会骗人 —— "没有这个应用"和"这个应用被下架了"对调用方是两件事: 前者该改
                // 应用 id, 后者该换个时间或换个应用。已有的会话不受影响, 那条路由不经过这里。
                case "APPLICATION_NOT_AVAILABLE" -> ActionStatus.STATE_CONFLICT;
                // 邀请的终局。票已经死了(用过/过期/撤回), 这不是换载荷能解决的, 得换一张票;
                // 409 让客户端认下"这张票到此为止"。
                case "INVITATION_CONSUMED", "INVITATION_EXPIRED", "INVITATION_REVOKED" ->
                        ActionStatus.STATE_CONFLICT;
                // 邀请状态机上的非法转移: 两个调用方同时消费同一张票时, 输的那个该重读。
                case "ILLEGAL_INVITATION_TRANSITION" -> ActionStatus.STATE_CONFLICT;
                // 三条归属不变量破了。这不该发生; 真发生了说明库里的数据自相矛盾, 调用方无论
                // 改什么载荷都过不去 —— 409 让它别重试, 500 会让它以为是服务挂了。
                case "SESSION_VERSION_MISMATCH", "SESSION_OWNER_MISMATCH",
                     "SESSION_CAPACITY_EXCEEDED" -> ActionStatus.STATE_CONFLICT;
                default -> ActionStatus.INVALID_ARGUMENT;
            };
        }
    }
}
