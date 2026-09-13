package com.luxera.companion.contracts.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * LAP v2 §64: <b>一张能进这一场的票</b> —— 聊天侧看得见的那部分。
 *
 * <p>聊天平台不需要理解票的规则(谁能铸、什么时候过期、用几次算完), 它只做一件事:
 * 把 {@link #joinUrl()} 作为一条消息发到对话里。所以这个 DTO 里没有任何判定方法 ——
 * 每加一个 {@code usable()} 之类的东西, 就是把一条平台规则抄进第二个模块, 而两份规则
 * 迟早会不一致。
 *
 * <h2>{@code token} 是这条记录里唯一会消失的字段</h2>
 * <p>{@code @JsonInclude(NON_NULL)} 不是装饰: 铸票的<em>那一次</em>响应里必须有明文
 * (否则分享链接根本拼不出来), 而此后任何一次列出票的响应里<b>必须没有</b>(库里只有
 * SHA-256, 见 R10 的"token 明文不进库"验收)。同一个类型在两种语境下由 null 区分,
 * 而不是靠两个类型 —— 两个类型会有两个字段名, 然后有人会把它们接错。
 *
 * @param invitationId 票的 id(管理面用, 不是凭证)
 * @param sessionId    进哪一场
 * @param token        明文 token —— <b>只在铸造的那一次响应里非空</b>
 * @param joinUrl      拼好的分享链接; 换成域名/加签名时全世界只有一处要改
 * @param role         拿这张票进来的人会拿到的 role
 * @param status       CREATED / CONSUMED / EXPIRED / REVOKED
 * @param maxUses      可用次数; null 表示不限
 * @param usedCount    已用次数
 * @param expiresAt    过期时间; null 表示不过期
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationInvitation(
        String invitationId,
        String sessionId,
        String token,
        String joinUrl,
        String role,
        String status,
        Integer maxUses,
        int usedCount,
        String expiresAt
) {

    /** 还能用吗 —— 看状态与次数, <b>不看时间</b>(过期的判定在平台那边, 它才知道现在几点)。 */
    public boolean open() {
        return "CREATED".equals(status)
                && (maxUses == null || usedCount < maxUses);
    }

    /** 剥掉明文的那一份 —— 落进消息 metadata 的是它。 */
    public ApplicationInvitation withoutToken() {
        return new ApplicationInvitation(invitationId, sessionId, null, joinUrl, role, status,
                maxUses, usedCount, expiresAt);
    }
}
