package com.luxera.companion.application.builtin.tictactoe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionHandlerKey;
import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.action.PendingActionProvider;
import com.luxera.companion.application.action.PendingActionRegistry;
import com.luxera.companion.application.spi.LapApplicationModule;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 井字棋 —— 迁移过来的那个参考应用。
 *
 * <p>它是"一个应用现在长什么样"的最短答案: <b>一份 manifest + 四个处理器 + 一个"什么时候能走"
 * 的回答</b>。没有实体、没有表、没有 Spring Data 仓库、没有会话管理 —— 棋盘就是资源。
 *
 * <p>和重构前对比, 少了这些类: {@code TicTacToeGameService}(390 行)、{@code GameSession}、
 * {@code GameSessionRepository}、{@code TicTacToeApplicationAdapter}。换成它之后,
 * 数字人那边也一并删掉了 {@code AgentRuntime.onApplicationEvent} 里的
 * {@code parseBoardState}、{@code evaluateAndDecideMove}、{@code boardToString} 与那句
 * {@code "O".equalsIgnoreCase(turn)} —— <b>井字棋的规则不再出现在数字人里</b>。
 *
 * <p><b>"该谁走"由本类回答, 不由调用方判断。</b>{@link #pendingActions} 是这条约束的落点:
 * 数字人问"我现在能做什么", 不是"现在是 O 吗"。轮到别人时它返回空列表, 于是 Agent 不可能
 * 自问自答地连下两步 —— 那条性质以前靠 {@code turn == "O"} 硬编码保证, 现在靠"轮次交出去了"
 * 这个更一般的陈述保证。
 */
@Component
public class TicTacToeApplication implements LapApplicationModule {

    static final String APP_ID = "com.luxera.tictactoe";
    static final String VERSION = "1.0.0";

    private static final String ACTION_CREATE = "game.create";
    private static final String ACTION_STATE = "game.state";
    private static final String ACTION_MAKE_MOVE = "game.make_move";
    private static final String ACTION_SURRENDER = "game.surrender";

    private static final String EVENT_START = "game.start";
    private static final String EVENT_MOVE = "game.move";
    private static final String EVENT_FINISH = "game.finish";

    private final ObjectMapper objectMapper;

    public TicTacToeApplication(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String manifestLocation() {
        return "applications/tictactoe/1.0.0/application-manifest.json";
    }

    @Override
    public void registerHandlers(ActionHandlerRegistry registry) {
        registry.register(key(ACTION_CREATE), this::create);
        registry.register(key(ACTION_STATE), this::state);
        registry.register(key(ACTION_MAKE_MOVE), this::makeMove);
        registry.register(key(ACTION_SURRENDER), this::surrender);
    }

    @Override
    public void registerPendingActions(PendingActionRegistry registry) {
        registry.register(APP_ID, VERSION, this::pendingActions);
    }

    private static ActionHandlerKey key(String actionId) {
        return ActionHandlerKey.of(APP_ID, VERSION, actionId);
    }

    // ─────────────────────────── 动作 ───────────────────────────

    /**
     * 开一局。目标 URI 是 {@code game://session/{sessionId}}, 其中 {@code sessionId} 就是平台
     * 分的会话 id —— 于是"这盘棋属于哪个会话"在 URI 上就看得见, 不需要再声明一次。
     *
     * <p>重复调用返回当前棋局而不是报错: 创建类动作的"已经在了"不是失败。真正的重复提交由幂等层
     * 兜住(同一个 key 直接重放), 这里兜的是"换了个 key 但棋局已在"。
     */
    private ActionOutcome create(ActionHandlerContext ctx) {
        ResourceView current = ctx.currentResource();
        if (current != null) {
            return ActionOutcome.success(stateOf(current));
        }
        ObjectNode state = TicTacToeGame.newState(
                objectMapper, ctx.principalId(), blankToNull(ctx.inputText("opponentPrincipalId")));
        ctx.write(state);
        ctx.emit(EVENT_START, eventData(state, ctx.principalId(), null));
        return ActionOutcome.success(state);
    }

    private ActionOutcome state(ActionHandlerContext ctx) {
        ResourceView current = ctx.currentResource();
        if (current == null) {
            return ActionOutcome.of(com.luxera.companion.contracts.application.ActionStatus.NOT_FOUND,
                    "GAME_NOT_FOUND", "棋局不存在: " + ctx.target());
        }
        return ActionOutcome.success(stateOf(current));
    }

    private ActionOutcome makeMove(ActionHandlerContext ctx) {
        ResourceView current = ctx.currentResource();
        if (current == null) {
            return ActionOutcome.of(com.luxera.companion.contracts.application.ActionStatus.NOT_FOUND,
                    "GAME_NOT_FOUND", "棋局不存在: " + ctx.target());
        }
        ObjectNode state = (ObjectNode) current.state().deepCopy();
        if (TicTacToeGame.finished(state)) {
            return ActionOutcome.fail("GAME_OVER", "这局已经结束了");
        }

        Integer position = ctx.inputInt("position");
        if (position == null || position < 0 || position >= TicTacToeGame.CELLS) {
            return ActionOutcome.fail("INVALID_POSITION",
                    "position 必须是 0.." + (TicTacToeGame.CELLS - 1) + " 的整数");
        }

        int seat = TicTacToeGame.seatOf(state, ctx.principalId());
        if (seat < 0) {
            int free = TicTacToeGame.freeSeat(state);
            if (free < 0) {
                return ActionOutcome.fail("NOT_A_PLAYER", "两个座位都有人了, 你不是对局者");
            }
            seat = free;
            state.withArray("seats").set(seat, new TextNode(ctx.principalId()));
        }
        String mark = TicTacToeGame.markForSeat(seat);

        String turn = state.path("turn").isTextual() ? state.get("turn").asText() : TicTacToeGame.MARK_X;
        if (!mark.equals(turn)) {
            return ActionOutcome.fail("NOT_YOUR_TURN", "现在轮到 " + turn + ", 你是 " + mark);
        }

        ArrayNode board = TicTacToeGame.board(state);
        if (TicTacToeGame.cell(board, position) != null) {
            return ActionOutcome.fail("CELL_TAKEN", "位置 " + position + " 已经有子了");
        }

        board.set(position, new TextNode(mark));
        state.put("moves", state.path("moves").asInt() + 1);

        String result = TicTacToeGame.evaluate(board);
        if (result != null) {
            state.put("winner", result);
            state.putNull("turn");
        } else {
            state.put("turn", TicTacToeGame.MARK_X.equals(mark)
                    ? TicTacToeGame.MARK_O : TicTacToeGame.MARK_X);
        }

        ctx.write(state);

        ObjectNode move = eventData(state, ctx.principalId(), position);
        move.put("mark", mark);
        ctx.emit(EVENT_MOVE, move);

        if (result != null) {
            ctx.emit(EVENT_FINISH, eventData(state, ctx.principalId(), null));
        }
        return ActionOutcome.success(state);
    }

    private ActionOutcome surrender(ActionHandlerContext ctx) {
        ResourceView current = ctx.currentResource();
        if (current == null) {
            return ActionOutcome.of(com.luxera.companion.contracts.application.ActionStatus.NOT_FOUND,
                    "GAME_NOT_FOUND", "棋局不存在: " + ctx.target());
        }
        ObjectNode state = (ObjectNode) current.state().deepCopy();
        if (TicTacToeGame.finished(state)) {
            return ActionOutcome.fail("GAME_OVER", "这局已经结束了");
        }
        int seat = TicTacToeGame.seatOf(state, ctx.principalId());
        if (seat < 0) {
            return ActionOutcome.fail("NOT_A_PLAYER", "你还没入座, 谈不上认输");
        }
        state.put("winner", TicTacToeGame.markForSeat(1 - seat));
        state.putNull("turn");
        ctx.write(state);
        ctx.emit(EVENT_FINISH, eventData(state, ctx.principalId(), null));
        return ActionOutcome.success(state);
    }

    // ─────────────────────────── 此刻能做什么 ───────────────────────────

    /**
     * 轮到你时才给出 {@code game.make_move}。终局、或没轮到你 → 空列表。
     *
     * <p>座位的处理在这里也要完整: 一个还没落座的 principal 如果还有空位, 它<em>可能</em>入座,
     * 而入座后执哪个记号是确定的({@code freeSeat} → {@code markForSeat})。因此"还没落座但轮次
     * 正好是空位的记号"这一种情况要报可用, 否则先手走完之后对手永远不被叫醒。
     */
    private List<String> pendingActions(ResourceView resource, InvocationContext ctx) {
        if (resource == null || ctx == null || ctx.principalId() == null) {
            return List.of();
        }
        JsonNode state = resource.state();
        if (TicTacToeGame.finished(state)) {
            return List.of();
        }
        int seat = TicTacToeGame.seatOf(state, ctx.principalId());
        if (seat < 0) {
            seat = TicTacToeGame.freeSeat(state);
            if (seat < 0) {
                return List.of();
            }
        }
        String turn = state.path("turn").isTextual()
                ? state.get("turn").asText() : TicTacToeGame.MARK_X;
        if (!TicTacToeGame.markForSeat(seat).equals(turn)) {
            return List.of();
        }
        return List.of(ACTION_MAKE_MOVE, ACTION_SURRENDER);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static JsonNode stateOf(ResourceView view) {
        JsonNode state = view.state();
        return state == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : state;
    }

    private static ObjectNode eventData(ObjectNode state, String actorId, Integer position) {
        ObjectNode data = state.objectNode();
        data.set("board", state.get("board"));
        data.set("seats", state.get("seats"));
        data.put("turn", state.path("turn").isTextual() ? state.get("turn").asText() : null);
        data.put("winner", state.path("winner").isTextual() ? state.get("winner").asText() : null);
        // 事件 id 靠它去重(manifest 的 idTemplate 引用 {moves})。手数在一局里单调递增,
        // 于是"同一手棋"重放时铸出的 id 相同, "另一手棋"必然不同 —— 这正是去重需要的那条界线。
        data.put("moves", state.path("moves").asInt());
        data.put("actorId", actorId);
        if (position != null) {
            data.put("position", position);
        }

        // 该通知谁: 除我之外的对局者。应用只陈述"这盘棋里还有谁", 谁是数字人由平台查安装表决定 ——
        // 本类里不出现 Human / Agent 分支, 这是开工前强制约束里那条的落点。
        ArrayNode notify = data.putArray("notifyPrincipalIds");
        for (String seat : TicTacToeGame.seats(state)) {
            if (seat != null && !seat.equals(actorId)) {
                notify.add(seat);
            }
        }

        // 实例级闸门: "有人在等下一手"。局终时 nextMark 为 null, 于是 game.finish 不叫醒任何人 ——
        // 叫醒的含义是"轮到你了", 而终局之后没有任何人在等谁出手。类型级闸门(manifest 的
        // triggersAgent)说的是"这类事件可以叫醒", 这里说的是"这一次要不要", 两者取与。
        data.put("agentTrigger", TicTacToeGame.nextMark(state) != null);
        return data;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
