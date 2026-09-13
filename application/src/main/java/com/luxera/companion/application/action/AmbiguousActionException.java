package com.luxera.companion.application.action;

import java.util.List;

/**
 * LAP v1: 同一个动作 id 有多个应用声明, 而 target 又没能消歧。
 *
 * <p>这不是"内部错误", 是<em>调用方必须解决的问题</em>: {@code game.make_move} 井字棋和五子棋
 * 都有, 只给动作 id 不给棋盘 URI, 平台没有资格替它挑一个 —— 猜错的后果是把子落到另一盘棋上。
 * 所以它必须翻成 {@code INVALID_ARGUMENT / AMBIGUOUS_ACTION} 并带上候选清单, 让调用方补全
 * target, 而不是随便选一个然后"看起来成功了"。
 */
public class AmbiguousActionException extends RuntimeException {

    private final String actionId;
    private final List<String> candidateApplicationIds;

    public AmbiguousActionException(String actionId, List<String> candidateApplicationIds) {
        super("AMBIGUOUS_ACTION: " + actionId + " 被多个应用声明 "
                + candidateApplicationIds + ", 请给出能消歧的 target");
        this.actionId = actionId;
        this.candidateApplicationIds = List.copyOf(candidateApplicationIds);
    }

    public String actionId() {
        return actionId;
    }

    public List<String> candidateApplicationIds() {
        return candidateApplicationIds;
    }
}
