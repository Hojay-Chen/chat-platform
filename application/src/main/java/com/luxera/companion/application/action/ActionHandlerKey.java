package com.luxera.companion.application.action;

/**
 * LAP v1: 动作处理器的注册键 —— <b>{@code (applicationId, version, actionId)}</b>。
 *
 * <p>为什么不是裸 {@code actionId}: {@code com.luxera.tictactoe} 与
 * {@code com.luxera.gomoku} 都会有 {@code game.make_move}。用 {@code Map<String, Handler>}
 * 会让后注册的<em>静默覆盖</em>先注册的 —— 症状是"五子棋能用, 井字棋变成五子棋的棋盘",
 * 而且没有任何报错。版本进键里则是为了 {@code application_version} 的历史可解释:
 * 1.0.0 与 1.0.1 的同一动作可以并存。
 */
public record ActionHandlerKey(String applicationId, String version, String actionId) {

    public static ActionHandlerKey of(String applicationId, String version, String actionId) {
        return new ActionHandlerKey(applicationId, version, actionId);
    }

    @Override
    public String toString() {
        return applicationId + "/" + version + "/" + actionId;
    }
}
