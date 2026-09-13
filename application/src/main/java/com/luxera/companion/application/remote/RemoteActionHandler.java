package com.luxera.companion.application.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.application.action.ActionHandler;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.manifest.ApplicationManifest;
import lombok.extern.slf4j.Slf4j;

/**
 * 一个 REMOTE 应用的某一个动作 —— <b>它就是"加一种运行时 = 加一个 handler"这句话本身。</b>
 *
 * <p>网关按 {@code (applicationId, version, actionId)} 找到它, 调 {@code execute}, 拿到
 * {@link ActionOutcome} —— 与内置应用交给它的东西是同一个类型。所以远端应用不需要在
 * {@code ActionGateway} 里有一条自己的路, 也不会因为"少走了一步"而与内置应用行为不一致。
 *
 * <p>它以 manifest 为不可变字段: 同一个远端应用的每个动作各持一个实例, 而它们共享同一个
 * manifest 对象 —— 转发时要用到的 {@code baseUrl} / {@code authRef} / 版本号都在那里。
 *
 * <h3>为什么这里要回写一次资源 (R14)</h3>
 *
 * <p>内置应用的 handler 自己调 {@code ctx.write(state)} —— 状态就长在 {@code resource} 表里。
 * 远端应用做不到这件事: 它的状态在平台进程之外(Python 的内存/数据库), 平台递不过去一个
 * 写句柄。于是"远端应用的状态"和"平台看得见的状态"之间会裂开: 棋下完了,
 * {@code GET /api/v1/resources?uri=...} 却 404 —— 而**所有参与者共享同一个 Resource**
 * (原则 7)对远端应用同样成立, 不然真人 A 落子、真人 B 根本读不到那盘棋。
 *
 * <p>所以远端每次成功写入后, 平台把返回的那个状态<b>投影</b>进 resource 行。这条投影:
 * <ul>
 *   <li><b>不改变谁是权威</b> —— 远端仍是唯一真相(它才判得出非法落子); 平台这一行是它的
 *       最新快照, 每次远端写入后刷新;</li>
 *   <li><b>只在写动作上做</b> —— {@code game.state} 这类读动作不会因为被读一次就让
 *       资源版本 +1(读不该产生副作用);</li>
 *   <li><b>失败不回滚调用</b> —— 远端已经改完了, 此刻回一个 409 等于对调用方说谎
 *       ("你这步没生效")。投影写不进去只记一条 warning, 下一次成功的调用会把它补上。</li>
 * </ul>
 */
@Slf4j
public final class RemoteActionHandler implements ActionHandler {

    private final ApplicationManifest manifest;
    private final RemoteApplicationInvoker invoker;

    public RemoteActionHandler(ApplicationManifest manifest, RemoteApplicationInvoker invoker) {
        this.manifest = manifest;
        this.invoker = invoker;
    }

    @Override
    public ActionOutcome execute(ActionHandlerContext context) {
        ActionOutcome outcome = invoker.invoke(manifest, context);
        mirrorState(context, outcome);
        return outcome;
    }

    /**
     * 把远端返回的状态投影进 {@code resource} 行 —— 让"多个参与者共享同一个 Resource"
     * 对远端应用也成立。规则见类注释: 只在成功的写动作上、只在远端给了 state 时、
     * 失败不改变调用结果。
     */
    private void mirrorState(ActionHandlerContext context, ActionOutcome outcome) {
        if (!outcome.succeeded() || context.spec().isRead()) return;
        JsonNode result = outcome.result();
        if (result == null || !result.hasNonNull("state")) return;
        try {
            context.write(result.get("state"));
        } catch (RuntimeException e) {
            log.warn("远端应用 {} 的状态投影没能写回 resource (target={}): {} —— "
                            + "远端已经改完了, 这次调用照旧算成功, 下次调用会补上这一行",
                    manifest.applicationId(), context.target(), e.toString());
        }
    }

    public ApplicationManifest manifest() {
        return manifest;
    }
}
