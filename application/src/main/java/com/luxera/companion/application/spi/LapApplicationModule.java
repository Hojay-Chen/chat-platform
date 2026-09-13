package com.luxera.companion.application.spi;

import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.action.PendingActionRegistry;

/**
 * 一个内置应用的宿主侧接口 —— <b>"我是一个 LAP 应用"</b>的完整含义。
 *
 * <p>三件事:
 * <ol>
 *   <li>我的 manifest 在哪(一份 {@code application-manifest.json});</li>
 *   <li>我的动作处理器怎么注册(按 {@code (applicationId, version, actionId)} 键);</li>
 *   <li>我如何回答"此刻这个资源上谁能做什么"。</li>
 * </ol>
 *
 * <p>注意宿主<em>不</em>让应用自己解析 manifest —— {@code ManifestRegistrar} 统一解析、校验、
 * 再回填。应用少一个可以绕开校验的口子, 平台也就少一种"某个应用偷偷不合法"的线上形态。
 *
 * <p>本接口取代 R3 的 {@code LocalApplicationProvider}: 那时应用是"一个必须被 Spring 扫到的
 * Bean", 现在应用是"一份声明 + 一组按三元组注册的 handler", 于是同一个应用的多版本可以并存,
 * 不同应用的同一个 {@code game.make_move} 也互不覆盖。
 */
public interface LapApplicationModule {

    /** 本应用 manifest 的 classpath 位置, 如 {@code applications/tictactoe/1.0.0/application-manifest.json}。 */
    String manifestLocation();

    /** 注册本应用的全部动作处理器。由 {@code ManifestRegistrar} 在启动时调用一次。 */
    void registerHandlers(ActionHandlerRegistry registry);

    /**
     * 注册"此刻哪些动作可用"的回答者。默认不注册 —— 不注册的应用在
     * {@code pendingActions} 上永远返回空, 也就是"Agent 不会主动对它做任何事"。
     *
     * <p>默认实现刻意是空: 一个应用要想被 Agent 主动操作, 必须自己说清楚"什么时候可以动",
     * 而不是靠平台把 manifest 里所有动作都报成可用 —— 那会让 Agent 在一个已经下完的棋盘上
     * 反复尝试落子。
     */
    default void registerPendingActions(PendingActionRegistry registry) {
        // 没有主动行为面的应用不需要实现。
    }
}
