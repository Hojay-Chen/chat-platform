package com.luxera.companion.contracts.application;

/**
 * LAP v2 §33/§59: <b>一场会话的引用</b> —— 一个自主调用方决定"该在哪一场里动手"所需的全部信息。
 *
 * <p>它不是 {@code contracts.chat.ApplicationSessionView}。那个类型是<em>聊天平台的读模型</em>
 * (给一段对话画一张卡片), 字段围着界面转; 这一个只回答两个问题:
 *
 * <pre>
 *   我是这一场里的人吗   → joined
 *   我还能进得去吗       → joinable()
 * </pre>
 *
 * <p>刻意<b>不叫 View</b>: 它不是"拿来显示的", 是"拿来选的"。{@link SessionRef#joinable()}
 * 存在的理由就是让调用方少一次注定失败的往返 —— 但这<b>不是准据</b>。真正说了算的是
 * {@code ParticipantService.requireAdmission}(CLOSED 优先于邀请、容量、角色降级都在那里),
 * 这里只是把它最外层的几条提前说一遍。两处不一致时以平台为准, 调用方必须能接住那次拒绝。
 *
 * @param sessionId         会话 id
 * @param applicationId     哪一份软件
 * @param status            CREATED / WAITING / ACTIVE / PAUSED / ENDED —— 十态在该表上的投影, 原值不翻译
 * @param visibility        PUBLIC / UNLISTED / PRIVATE —— <b>可见性, 不是准入</b>。它决定"我在目录里
 *                          看不看得见这一场", 不决定"我能不能进" —— 后者是 {@code joinPolicy}
 * @param joinPolicy        OPEN / INVITE_ONLY / CLOSED
 * @param participantCount  当前在场人数
 * @param maxParticipants   上限
 * @param joined            调用方(即 {@code ctx} 里那个 principal)此刻是不是 ACTIVE 参与者
 * @param ownerPrincipalType 会话行上记的"出处"(不是权柄 —— 权柄在参与者的 role 上)
 * @param ownerPrincipalId  同上
 * @param conversationId    挂在哪段对话上(§85), 不是从对话里开出来的为 null
 */
public record SessionRef(
        String sessionId,
        String applicationId,
        String status,
        String visibility,
        String joinPolicy,
        int participantCount,
        int maxParticipants,
        boolean joined,
        String ownerPrincipalType,
        String ownerPrincipalId,
        String conversationId
) {

    /** 这一场还活着吗。与 {@code ApplicationSessionView.live()} 同一个判据, 一个字都不多。 */
    public boolean live() {
        return !"ENDED".equals(status) && !"EXPIRED".equals(status);
    }

    /**
     * 此刻加入<em>会成功</em>吗 —— 尽力而为的判断, 见类注释。
     *
     * <p>三条:
     * <ul>
     *   <li>已经<b>在场</b>的人永远进得去。再调一次 join 是"恢复"(LEFT → ACTIVE)或空操作,
     *       与 {@code ParticipantService.join} 对已有行的处理一致 —— 那里不查容量也不查策略。</li>
     *   <li>{@code OPEN} 才谈得上"陌生人进来"。{@code INVITE_ONLY} 要一张票({@code viaInvitation}),
     *       而票不在这个类型能看见的世界里 —— 它是一次性的明文, 由持有者拿着走。</li>
     *   <li>满了就进不去。这一条只对<em>新</em>参与者成立, 所以它只在没 joined 时才算。</li>
     * </ul>
     */
    public boolean joinable() {
        if (!live()) {
            return false;
        }
        if (joined) {
            return true;
        }
        return "OPEN".equals(joinPolicy) && participantCount < maxParticipants;
    }
}
