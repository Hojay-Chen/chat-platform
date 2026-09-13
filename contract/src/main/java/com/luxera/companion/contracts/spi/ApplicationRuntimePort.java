package com.luxera.companion.contracts.spi;

import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.SessionRef;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1 — the digital human's <em>only</em> way to touch an application.
 *
 * <p>Implemented by {@code application-platform}. A human client, an Agent and an external MCP
 * client all end up in the same {@code ApplicationGateway}; this port is simply the in-process
 * face of it. There is no agent-specific entry point anywhere.
 *
 * <p>The four methods answer the four questions an autonomous actor asks, in order:
 * <ol>
 *   <li>{@link #capabilities()} — what kinds of things can be done in this world?</li>
 *   <li>{@link #applicationsFor(String)} — which applications offer that capability?</li>
 *   <li>{@link #read(String)} and {@link #pendingActions(String, InvocationContext)} — what is
 *       true right now, and what may I do about it?</li>
 *   <li>{@link #execute(ActionRequest, InvocationContext)} — do it.</li>
 * </ol>
 *
 * <p><b>{@link #pendingActions} is what keeps application rules out of the digital human.</b> The
 * pre-LAP runtime asked {@code if (turn == "O")} inside {@code AgentRuntime}; now the application
 * answers "here is what you may do" and an empty list means "nothing". Whose turn it is, whether
 * the game is over, whether a reminder is already acknowledged — all of it stays in the
 * application, where it belongs.
 */
public interface ApplicationRuntimePort {

    /** The platform's capability catalogue. */
    List<CapabilityView> capabilities();

    /** Published applications declaring {@code capabilityId}; empty if none. */
    List<ApplicationView> applicationsFor(String capabilityId);

    /** Every action a published application exposes. */
    List<ActionSpec> actionsOf(String applicationId);

    /** The resource as it currently stands, or empty if it does not exist. */
    Optional<ResourceView> read(String resourceUri);

    /**
     * Actions the given principal may perform against this resource right now, annotated with the
     * application author's {@code agentHint}. Empty means "do nothing" — not an error.
     */
    List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx);

    /**
     * Perform one action. Implementations must enforce the permission model, idempotency and
     * optimistic concurrency; callers may treat a non-{@code SUCCESS} status as final.
     */
    ActionResponse execute(ActionRequest request, InvocationContext ctx);

    /**
     * 保证这个 principal 在某个应用里<b>有一个正在进行的会话</b> —— <b>幂等</b>, 返回它的 id。
     *
     * <p>LAP v2 之前这里叫 {@code ensureInstalled}: 那时的模型是"先安装, 才有会话"。v2 把安装
     * 整个拿掉了 —— 用户无需安装任何东西, 剩下的是"<em>我在这个应用里有一个实例</em>", 那正是
     * Session。于是同一个位置上的问题从"我允许这个应用为我做事吗"变成了"我在哪里做这件事",
     * 答案从一个布尔值变成了一个 id。
     *
     * <p>为什么数字人需要它: 数字人有些功能天然要落到某个应用上(提醒就是第一个), 而"用户开了
     * 什么会话"是用户的选择, 不该由数字人在启动时替所有人批量创建, 也不该由部署脚本往数据库里
     * 塞行。于是改成"用的时候保证一下": 第一次用到时建一个并把调用方记为 OWNER, 之后每次都是
     * 无操作。
     *
     * <p>它<em>只</em>保证会话存在, 不保证会话里发生了什么 —— 动作仍然要另外走
     * {@link #execute}。
     *
     * @return 该 principal 在这个应用里可用的会话 id; 永不返回 null
     * @throws RuntimeException 应用不存在、没有已发布版本、或调用方身份不合法时由实现抛出
     */
    String ensureSession(String applicationId, InvocationContext ctx);

    // ─────────────────────────── 参与 ───────────────────────────

    /**
     * LAP v2 §59/§60: <b>调用方在这个应用里看得见的会话</b>, 最近活跃的在前。
     *
     * <p>集合是两部分的并集, 而且只有这两部分:
     * <ol>
     *   <li>调用方<b>此刻在场</b>的({@code status='ACTIVE'} 的参与者行);</li>
     *   <li>任何人<b>都进得去</b>的({@code joinPolicy=OPEN} 且还活着)。</li>
     * </ol>
     * 第一部分是"我在哪儿", 第二部分是"哪儿还开着门"。{@code INVITE_ONLY} / {@code CLOSED} 的
     * 陌生人会话<b>不出现</b> —— 那不是一个"看不见"的问题, 是一个"不该看见"的问题: 一场默认
     * 策略的会话属于开它的人, 它出现在别人的列表里只会换来一次必然被拒的 join。
     *
     * <p><b>退场过的会话不在这里面。</b> "我退出的那一场"是历史, 但那不是这个方法回答的问题 ——
     * 它回答的是"我现在能在哪儿动手"。想让一个 {@code LEFT} 的人回来, 靠的是他手里那张票
     * (见 {@link #joinByInvitation}), 而不是靠他在列表里重新找到那一场: 后者等于让任何人都能
     * 把自己塞回一个他已经退出的会话。
     *
     * <p><b>实现可以截断</b>(平台侧上限 50), 但截断必须是"尾部截断"—— 参与过的那些一条都不许被
     * 挤掉。数字人正是靠这一档认出"我刚才就在这场里"。
     *
     * <p>为什么这件事必须由平台回答、而不能由调用方自己拼: 判据是 {@code joinPolicy} 与参与者行
     * 的<b>连接</b>, 而 {@code joinPolicy} 不是应用 manifest 里的东西(它是会话行上的运行期选择)。
     * 让调用方去拼, 就得把会话表交出去。
     *
     * @return 永远不是 null; 没有可见会话时是空列表
     */
    List<SessionRef> sessionsOf(String applicationId, InvocationContext ctx);

    /**
     * 凭一张票进到这个会话里 —— 这是"<b>别人</b>怎么进来"的唯一一条路, 也是数字人接受邀请的方式。
     *
     * <p>与 {@link #joinSession} 的分别不是方便程度, 是<em>凭什么</em>: 那个说"我就是要去这一场"
     * (只对 {@code OPEN} 的会话有效), 这个说"我手里有开门的凭据"。{@code INVITE_ONLY} 是会话的
     * 默认策略, 所以后者才是常态 —— 分享链接点开的那个真人走的就是这条路。
     *
     * <p><b>为什么数字人也要走兑票, 而不是"平台知道它是被邀请的就放它进"。</b> 因为那两者从来不是
     * 一回事: "我知道你是 companion-7"不等于"我有权替你加入"。{@code InvitationService} 的类注释
     * 把这条写死了 —— 定向邀请也只是票上的一个约定, 不构成第二条放进来的路。于是数字人在这件事上
     * 与真人<em>逐字相同</em>: 拿到同一段 token, 兑同一扇门。§130 原则 4 说的"Agent 只是另一种
     * 用户", 在代码里就是这一行。
     *
     * <p>票会被消耗({@code usedCount+1}), 加入失败则不消耗 —— 见 {@code InvitationService.consume}。
     * 同一个人兑同一张票的第二次不烧名额, 所以"恢复一个退场过的自己"是安全的。
     *
     * @return 票指向的会话 id —— 调用方未必预先知道它, 所以这里必须回一个
     * @throws RuntimeException 票不存在 / 已撤销 / 已过期 / 已用尽 / 会话已结束 / 人满 / 身份不合法
     */
    String joinByInvitation(String token, InvocationContext ctx);

    /**
     * 加入一场<b>已经存在、且不拒绝陌生人</b>的会话。幂等: 已经在里面就什么都不做。
     *
     * <p>与 {@link #ensureSession} 的分工是 §130 原则 2 本身: 那个问"我该在哪一场里",
     * 这个问"我就是要去这一场"。{@link #sessionsOf} 报出来的 {@code OPEN} 会话要进得去,
     * 靠的就是这一个方法 —— 没有它, 那一档发现出来的"开着门"就没有对应的敲门动作。
     *
     * <p>已经退场({@code LEFT})过的人再调一次, 是<b>恢复</b>: 同一行被置回 ACTIVE,
     * 他重新拿到授权。所以"退出"不是"删除" —— 见 {@link #leaveSession}。
     *
     * <p>返回 void 而不是会话 id: 调用方手里已经有那个 id 了, 再回一个同值的东西只会让
     * {@code join(x).equals(x)} 这种恒真的断言有机会被写进测试里。它回答的是"做到没有",
     * 做不到就抛 —— 与 {@link #ensureSession} 的"建不出来就抛"是同一种契约。
     *
     * @throws RuntimeException 会话不存在 / 已结束 / 不接受加入({@code CLOSED} 或需要邀请) /
     *                          人满 / 调用方身份不合法
     */
    void joinSession(String sessionId, InvocationContext ctx);

    /**
     * 退出这一场。把调用方那一行置成 {@code LEFT} 并收回他的授权。
     *
     * <p><b>不退场会话本身, 也不删任何东西。</b> 人走了局面还在: 一局棋下到一半走了一个人,
     * 那是残局, 不是"这局没了"。会话的收尾有它自己的两条路(DELETE 与回收器)——
     * 不该由一次 leave 替所有人做这个决定。
     *
     * <p>不在场的人调用它 → 抛({@code NOT_A_PARTICIPANT}), 不是静默成功。
     * "我退出了一个我从没进过的房间"如果算成功, 那它就没有任何时刻会失败。
     *
     * @throws RuntimeException 会话不存在, 或调用方本来就不在场
     */
    void leaveSession(String sessionId, InvocationContext ctx);
}
