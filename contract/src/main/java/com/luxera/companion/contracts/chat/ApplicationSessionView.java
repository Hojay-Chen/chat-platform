package com.luxera.companion.contracts.chat;

import java.util.List;

/**
 * LAP v2 §64: <b>一次"在用某个应用"</b> —— 聊天侧看得见的那部分会话。
 *
 * <p>这是 §16 那个形状在聊天语境下的读模型。刻意<b>不复用</b> REST 的
 * {@code SessionResponse}: 那个类型长在 {@code /api/v1} 上, 它的字段随 HTTP 契约演进
 * (谁改了它, 所有客户端跟着改); 而这个类型的消费者是聊天平台, 它在进程内, 它要的是
 * "这段对话里开着哪些东西"所需的那几列。
 *
 * <p>两者的<em>含义</em>必须一致 —— 同一个会话在两条路上说出来的 {@code status} 得是同一个词。
 * 所以这里不做任何翻译: 状态就是十态原值(见 {@code ApplicationSessionRecord}), 聊天侧不
 * 维护一份"哪些状态算活着"的映射。
 *
 * <h2>为什么没有 owner</h2>
 * <p>会话行上确实有 {@code owner_principal_type/id}, 但那是<em>出处</em>, 不是权柄
 * (见 {@code ApplicationSessionService} 的第二条不变量)。谁说了算由参与者的
 * {@code role} 回答, 而"我在这一场里是谁"由 {@link ApplicationLaunchResponse#participant()} 回答。
 * 把这个字段抄进来, 就会出现两个地方回答同一个问题。
 *
 * @param sessionId        会话 id —— 拿它去 action / resource / invitation 三条路上都认
 * @param applicationId    哪一份软件
 * @param version          哪一版(版本<em>号</em>, 不是版本行的 id —— 后者对人没有意义)
 * @param status           十态原值
 * @param visibility       PUBLIC / UNLISTED / PRIVATE
 * @param joinPolicy       OPEN / INVITE_ONLY / CLOSED
 * @param minParticipants  低于它时写动作被挡, 读不受影响
 * @param maxParticipants  上限
 * @param participantCount <em>当前在场</em>的人数, 不是历史累计
 * @param conversationId   挂在哪段对话上(§85); 不是从对话里开的会话为 null
 * @param capabilities     问我(调用方)在这一场里能做什么 —— 空列表是一种真实的授权状态(观察者),
 *                         不是"没这号人"的表示
 * @param createdAt        创建时间(ISO-8601 文本; 契约层不引 java.time 之外的格式化约定)
 * @param startedAt        进入 ACTIVE 的时间, 可能为 null
 * @param endedAt          结束时间, 可能为 null
 * @param lastActiveAt     最后一次动作的时间
 */
public record ApplicationSessionView(
        String sessionId,
        String applicationId,
        String version,
        String status,
        String visibility,
        String joinPolicy,
        int minParticipants,
        int maxParticipants,
        int participantCount,
        String conversationId,
        List<String> capabilities,
        String createdAt,
        String startedAt,
        String endedAt,
        String lastActiveAt
) {

    public ApplicationSessionView {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    /** 这一场还活着吗 —— {@code ENDED} / {@code EXPIRED} 之外都算。 */
    public boolean live() {
        return !"ENDED".equals(status) && !"EXPIRED".equals(status);
    }
}
