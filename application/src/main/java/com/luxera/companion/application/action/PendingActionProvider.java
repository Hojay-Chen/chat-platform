package com.luxera.companion.application.action;

import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;

import java.util.List;

/**
 * LAP v1: <b>应用回答"此刻这个资源上, 谁能做什么"。</b>
 *
 * <p>这是整个重构里最值钱的一个接口。重构前 {@code AgentRuntime} 里写着
 * {@code if (!"O".equalsIgnoreCase(turn)) return;} —— 数字人<em>自己</em>知道井字棋的规则,
 * 于是加第二个游戏必须改数字人。现在这条规则由应用回答, 数字人只问一句"我能做什么",
 * 得到空列表就什么都不做。
 *
 * <p>返回的是<em>动作 id</em>而不是动作定义: 动作的定义是 manifest 里声明的, 平台拿 id 去
 * manifest 里取 {@code ActionSpec}(连同作者写的 {@code agentHint}) 再交给调用方。应用在这里
 * 多写一个字都是重复。
 *
 * <p><b>必须考虑调用方是谁。</b>同一个棋盘, 轮到 X 时 X 能做 {@code game.make_move},
 * 轮到 O 时 X 什么都做不了。参数里给的就是调用方身份 —— 但也仅此而已: 应用不该去问
 * "你是人还是 Agent", 那是平台的分支, 不是应用的。
 */
@FunctionalInterface
public interface PendingActionProvider {

    /**
     * @param resource 调用方正在看的资源; 不存在时为 {@code null}(创建类动作的前一刻)
     * @return 此刻可用的动作 id; 空表示"什么都不用做", 不是错误
     */
    List<String> pendingActionIds(ResourceView resource, InvocationContext ctx);
}
