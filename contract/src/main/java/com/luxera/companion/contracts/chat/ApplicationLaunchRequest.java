package com.luxera.companion.contracts.chat;

/**
 * LAP v2 §64: <b>"在这段对话里把它打开"</b> —— 聊天平台递给应用平台的那张单子。
 *
 * <h2>为什么 {@code conversationId} 在这一层, 而不在 REST 的路径里</h2>
 * <p>因为这是两件事的两个位置。聊天端点的路径是
 * {@code /conversations/{conversationId}/applications}, 那是<em>路由</em> —— 它说的是"这段对话";
 * 而这个字段是<em>载荷</em> —— 它说的是"开出来的这个会话要记住自己属于哪段对话"。
 * 应用平台不认识"路由", 它只认识"一个会话挂在哪段对话上"。
 *
 * <p>它在 §85 里落成 {@code application_session.conversation_id} 一列, 于是"这段对话里开着
 * 哪些应用"不必去问聊天平台, 也不必把两张表在内存里对一遍。
 *
 * <h2>没有 {@code principalType} / {@code principalId}</h2>
 * <p>§36: 身份从 Context 获得, 请求里不出现 {@code userId} / {@code companionId} / {@code agentId}。
 * 这个 DTO 里一个都没有 —— 谁在开这个应用, 由 {@code ApplicationCatalogPort} 的
 * {@code InvocationContext} 参数回答, 而那个上下文是聊天平台从已认证的身份构造的。
 *
 * @param applicationId     要打开哪个应用(聊天端点从路径里填进来)
 * @param conversationId    这段对话 —— 会话行的 {@code conversation_id}
 * @param visibility        可选, {@code PUBLIC} / {@code UNLISTED} / {@code PRIVATE}; 空由平台定
 * @param joinPolicy        可选, {@code OPEN} / {@code INVITE_ONLY} / {@code CLOSED}; 空由平台定
 * @param minParticipants   可选, 低于它时写动作会被挡(读不受影响)
 * @param maxParticipants   可选, 上限
 */
public record ApplicationLaunchRequest(
        String applicationId,
        String conversationId,
        String visibility,
        String joinPolicy,
        Integer minParticipants,
        Integer maxParticipants
) {

    /** 在对话里打开一个应用 —— 最常用的一种。 */
    public static ApplicationLaunchRequest in(String applicationId, String conversationId) {
        return new ApplicationLaunchRequest(applicationId, conversationId, null, null, null, null);
    }
}
