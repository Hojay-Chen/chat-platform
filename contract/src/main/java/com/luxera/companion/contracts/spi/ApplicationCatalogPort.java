package com.luxera.companion.contracts.spi;

import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.chat.ApplicationCard;
import com.luxera.companion.contracts.chat.ApplicationInvitation;
import com.luxera.companion.contracts.chat.ApplicationLaunchRequest;
import com.luxera.companion.contracts.chat.ApplicationLaunchResponse;
import com.luxera.companion.contracts.chat.ApplicationSessionView;

import java.util.List;
import java.util.Optional;

/**
 * LAP v2 §63/§64: <b>聊天平台看见应用生态的那扇窗</b>。
 *
 * <p>这一层是"聊天平台必须能做 §63 那几件事"与"聊天平台不得依赖应用平台"两条要求之间
 * 唯一的解。它是本仓库里第三个这样的端口(前两个是 {@link ChatWorldPort} 与
 * {@link CompanionDirectoryPort}), 形状也刻意相同: <em>方法少、参数是契约类型、返回值是视图
 * 而不是实体、没有一个方法提到实现方的类名。</em>
 *
 * <h2>调用方要自己说清楚"我是谁", 而且平台信它</h2>
 * <p>每个方法都收一个 {@link InvocationContext}。这是<em>进程内</em>的信任模型: 聊天平台是
 * 可信的调用方, 它从已认证的会话里知道"现在是真人 U", 于是构造
 * {@code InvocationContext.human(userId, correlationId)}。应用平台不会去重新验证这个身份 ——
 * 就像 {@code ChatWorldPort} 也不会验证数字人自报的 companionId 一样。
 *
 * <p><b>但这不是把校验拱手让人。</b> 越过这个端口之后, 应用平台仍然会用它自己那套判据
 * (参与者行、{@code session_permission}、§4.1 的可用性)回答"这个身份在这一场里能不能做这件事"。
 * 端口省掉的是<em>认证</em>(票在聊天平台那边已经验过了), 不是<em>授权</em>。
 *
 * <p>因此 {@code InvocationContext} 里的 {@code principalType} <b>必须显式写下</b> ——
 * {@code InternalPrincipalResolver} 不接受空值, 理由见那个类。聊天平台今天只会构造
 * {@code HUMAN}, 但那是它写下来的, 不是平台猜的。
 *
 * <h2>为什么没有"给某个对话列出应用事件"的方法</h2>
 * <p>因为那需要一整套按对话过滤的事件订阅语义, 而 §64 的第七个 DTO 恰恰被定为"复用
 * {@code ApplicationEvent}"(见 {@code contracts.chat} 的包注释)。R12 的这三件事
 * ——看见、打开、分享—— 不需要它; 真有需要时该加的是订阅侧的查询, 不是在这里开一个口子。
 */
public interface ApplicationCatalogPort {

    // ── 看 ──────────────────────────────────────────────────────────────────

    /**
     * 此刻<b>能开新会话</b>的全部应用, 做成卡片。
     *
     * <p>过滤用的是 §4.1 那张表的第二列({@code allowsNewSession}), 不是第一列 ——
     * 卡片上有一个"打开"按钮, 所以"在市场上看得见但开不了"的应用不该出现在这里:
     * 那会给用户一个按下去必然 409 的按钮。
     *
     * <p>返回空列表是合法的("现在没有能开的东西"), 不返回 null。
     */
    List<ApplicationCard> cards(InvocationContext context);

    /**
     * 一个应用的卡片。应用不存在 → 空。
     *
     * <p>没有这个方法的话, "打开了某个应用之后要落一条卡片消息"就得先把整个目录拉下来
     * 再筛一遍 —— 那是一个每次开应用都发生的全表扫描, 而且它把"我只要这一个"这件事
     * 藏进了一个 filter 里。
     */
    Optional<ApplicationCard> card(InvocationContext context, String applicationId);

    /** 一段对话里开着的那几个会话。没开过就是空列表。 */
    List<ApplicationSessionView> sessionsOfConversation(InvocationContext context, String conversationId);

    /** 看一个会话。调用方不是它的参与者 → 抛(拒绝, 不是空 —— 见 §130 原则 3)。 */
    ApplicationSessionView session(InvocationContext context, String sessionId);

    // ── 开 ──────────────────────────────────────────────────────────────────

    /**
     * 打开一个应用, 并把 {@code conversationId} 记在会话行上。
     *
     * <p><b>每次都新建一个会话</b>, 不复用。这与 {@code ensureSession}(进程内那条幂等的路)
     * 的区别就是 §130 原则 2 本身: Application 是软件, Session 是<em>运行实例</em>。
     * 在一段对话里点第二次"打开井字棋", 用户要的是再下一局, 不是被塞回上一局。
     */
    ApplicationLaunchResponse launch(InvocationContext context, ApplicationLaunchRequest request);

    // ── 分享 ────────────────────────────────────────────────────────────────

    /**
     * 铸一张进这一场的票, 交给调用方处置(聊天平台会把它作为一条消息发到对话里)。
     *
     * <p>{@code role} 为空时是 {@code MEMBER}; {@code maxUses} 为空表示不限次数。
     * 只有会话的主人能铸票 —— 这条判据在应用平台里, 不在聊天平台里。
     *
     * <p>返回的 {@link ApplicationInvitation} 里 {@code token} 与 {@code joinUrl}
     * <b>这一次非空, 此后永远为空</b>(库里只有哈希)。见那个类型的注释。
     */
    ApplicationInvitation invite(InvocationContext context, String sessionId, String role, Integer maxUses);
}
