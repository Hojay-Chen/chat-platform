package com.luxera.companion.application.builtin.gomoku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionHandlerKey;
import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.action.PendingActionRegistry;
import com.luxera.companion.application.spi.LapApplicationModule;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 五子棋 —— <b>唯一一个"为了证明平台而加"的应用</b>。
 *
 * <p>它与 {@link com.luxera.companion.application.builtin.tictactoe.TicTacToeApplication}
 * 声明了<em>完全相同的动作 id</em>({@code game.create} / {@code game.state} /
 * {@code game.make_move} / {@code game.surrender})。这是刻意的: 平台的 Handler 注册表键是
 * {@code (applicationId, version, actionId)}, 而 {@code ActionResolver} 靠资源 URI 的模板消歧 ——
 * 两件事都只有在"两个应用真的撞了动作 id"的时候才被验证。同 capability、同动作名、
 * 不同的资源模板, 正是 {@code ManifestRegistrarTest} 与 {@code CapabilityResolverTest} 要的形态。
 *
 * <p><b>它与井字棋的差别只有三处</b>: 棋盘大小(225 vs 9)、胜负判定(五连 vs 三连)、
 * 资源模板({@code gomoku://match/{sessionId}} vs {@code game://session/{sessionId}})。
 * 其余全部一样 —— 因为"两个 principal 轮流在网格上下子"这件事与棋种无关。
 *
 * <p><b>资源 URI 里承载会话的那一段仍然叫 {@code sessionId}</b>, 尽管前半段换了 scheme。
 * 这是平台约定({@code ActionResolver.SESSION_VARIABLE}): URI 上必须看得见"这盘棋属于哪个会话",
 * 否则归属链就断在 URI 这一层, 而归属链是权限模型的地基。
 */
@Component
public class GomokuApplication implements LapApplicationModule {

    static final String APP_ID = "com.luxera.gomoku";
    static final String VERSION = "1.0.0";

    private static final String ACTION_CREATE = "game.create";
    private static final String ACTION_STATE = "game.state";
    private static final String ACTION_MAKE_MOVE = "game.make_move";
    private static final String ACTION_SURRENDER = "game.surrender";

    private static final String EVENT_START = "game.start";
    private static final String EVENT_MOVE = "game.move";
    private static final String EVENT_FINISH = "game.finish";

    private final ObjectMapper objectMapper;

    public GomokuApplication(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String manifestLocation() {
        return "applications/gomoku/1.0.0/application-manifest.json";
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

    private ActionOutcome create(ActionHandlerContext ctx) {
        ResourceView current = ctx.currentResource();
        if (current != null) {
            return ActionOutcome.success(stateOf(current));
        }
        ObjectNode state = GomokuGame.newState(
                objectMapper, ctx.principalId(), blankToNull(ctx.inputText("opponentPrincipalId")));
        ctx.write(state);
        ctx.emit(EVENT_START, eventData(state, ctx.principalId(), null, null));
        return ActionOutcome.success(state);
    }

    private ActionOutcome state(ActionHandlerContext ctx) {
        ResourceView current = ctx.currentResource();
        if (current == null) {
            return ActionOutcome.of(ActionStatus.NOT_FOUND,
                    "MATCH_NOT_FOUND", "棋局不存在: " + ctx.target());
        }
        return ActionOutcome.success(stateOf(current));
    }

    private ActionOutcome makeMove(ActionHandlerContext ctx) {
        ResourceView current = ctx.currentResource();
        if (current == null) {
            return ActionOutcome.of(ActionStatus.NOT_FOUND,
                    "MATCH_NOT_FOUND", "棋局不存在: " + ctx.target());
        }
        ObjectNode state = (ObjectNode) current.state().deepCopy();
        if (GomokuGame.finished(state)) {
            return ActionOutcome.fail("GAME_OVER", "这局已经结束了");
        }

        Integer position = ctx.inputInt("position");
        if (position == null || position < 0 || position >= GomokuGame.CELLS) {
            return ActionOutcome.fail("INVALID_POSITION",
                    "position 必须是 0.." + (GomokuGame.CELLS - 1)
                            + " 的整数, 格子序号 = 行 × " + GomokuGame.SIZE + " + 列");
        }

        int seat = GomokuGame.seatOf(state, ctx.principalId());
        if (seat < 0) {
            int free = GomokuGame.freeSeat(state);
            if (free < 0) {
                return ActionOutcome.fail("NOT_A_PLAYER", "两个座位都有人了, 你不是对局者");
            }
            seat = free;
            state.withArray("seats").set(seat, new TextNode(ctx.principalId()));
        }
        String mark = GomokuGame.markForSeat(seat);

        String turn = state.path("turn").isTextual() ? state.get("turn").asText() : GomokuGame.MARK_X;
        if (!mark.equals(turn)) {
            return ActionOutcome.fail("NOT_YOUR_TURN", "现在轮到 " + turn + ", 你是 " + mark);
        }

        ArrayNode board = GomokuGame.board(state);
        if (GomokuGame.cell(board, position) != null) {
            return ActionOutcome.fail("CELL_TAKEN", "位置 " + position + " 已经有子了");
        }

        board.set(position, new TextNode(mark));
        state.put("moves", state.path("moves").asInt() + 1);

        String result = GomokuGame.evaluate(board);
        if (result != null) {
            state.put("winner", result);
            state.putNull("turn");
        } else {
            state.put("turn", GomokuGame.MARK_X.equals(mark)
                    ? GomokuGame.MARK_O : GomokuGame.MARK_X);
        }

        ctx.write(state);

        ObjectNode move = eventData(state, ctx.principalId(), position, mark);
        ctx.emit(EVENT_MOVE, move);

        if (result != null) {
            ctx.emit(EVENT_FINISH, eventData(state, ctx.principalId(), null, null));
        }
        return ActionOutcome.success(state);
    }

    private ActionOutcome surrender(ActionHandlerContext ctx) {
        ResourceView current = ctx.currentResource();
        if (current == null) {
            return ActionOutcome.of(ActionStatus.NOT_FOUND,
                    "MATCH_NOT_FOUND", "棋局不存在: " + ctx.target());
        }
        ObjectNode state = (ObjectNode) current.state().deepCopy();
        if (GomokuGame.finished(state)) {
            return ActionOutcome.fail("GAME_OVER", "这局已经结束了");
        }
        int seat = GomokuGame.seatOf(state, ctx.principalId());
        if (seat < 0) {
            return ActionOutcome.fail("NOT_A_PLAYER", "你还没入座, 谈不上认输");
        }
        state.put("winner", GomokuGame.markForSeat(1 - seat));
        state.putNull("turn");
        ctx.write(state);
        ctx.emit(EVENT_FINISH, eventData(state, ctx.principalId(), null, null));
        return ActionOutcome.success(state);
    }

    // ─────────────────────────── 此刻能做什么 ───────────────────────────

    /** 与井字棋同一条判断: 轮到你了才给 {@code game.make_move}; 终局或没轮到你 → 空列表。 */
    private List<String> pendingActions(ResourceView resource, InvocationContext ctx) {
        if (resource == null || ctx == null || ctx.principalId() == null) {
            return List.of();
        }
        JsonNode state = resource.state();
        if (GomokuGame.finished(state)) {
            return List.of();
        }
        int seat = GomokuGame.seatOf(state, ctx.principalId());
        if (seat < 0) {
            seat = GomokuGame.freeSeat(state);
            if (seat < 0) {
                return List.of();
            }
        }
        String turn = state.path("turn").isTextual()
                ? state.get("turn").asText() : GomokuGame.MARK_X;
        if (!GomokuGame.markForSeat(seat).equals(turn)) {
            return List.of();
        }
        return List.of(ACTION_MAKE_MOVE, ACTION_SURRENDER);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static JsonNode stateOf(ResourceView view) {
        JsonNode state = view.state();
        return state == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : state;
    }

    /**
     * 事件载荷 —— <b>刻意不带整块棋盘</b>, 只带"这一步发生了什么"。
     *
     * <p>井字棋的棋盘只有 9 格, 抄进事件里无所谓; 五子棋是 225 格, 每落一子抄一遍会让
     * 日志、事件表(outbox)和每一次跨模块投递都平白胖一个数量级。要看局面的人应该去读
     * Resource —— 那本来就是同一个资源的另一条读路径, 而"事件说发生了什么、Resource 说现在
     * 什么样"是 LAP 里两条不同的读法, 不该混成一条。
     */
    private static ObjectNode eventData(ObjectNode state, String actorId, Integer position, String mark) {
        ObjectNode data = state.objectNode();
        data.put("turn", state.path("turn").isTextual() ? state.get("turn").asText() : null);
        data.put("winner", state.path("winner").isTextual() ? state.get("winner").asText() : null);
        data.put("moves", state.path("moves").asInt());
        data.put("actorId", actorId);
        if (position != null) {
            data.put("position", position);
            data.put("row", GomokuGame.rowOf(position));
            data.put("col", GomokuGame.colOf(position));
        }
        if (mark != null) {
            data.put("mark", mark);
        }

        ArrayNode seats = state.putArray("seats");
        for (String seat : GomokuGame.seats(state)) {
            seats.add(seat);
        }

        // 该通知谁: 除我之外的对局者。应用只说"这盘棋里还有谁", 谁是数字人由平台查安装表决定。
        ArrayNode notify = data.putArray("notifyPrincipalIds");
        for (String seat : GomokuGame.seats(state)) {
            if (seat != null && !seat.equals(actorId)) {
                notify.add(seat);
            }
        }

        // 实例级闸门: 局终时 nextMark 为 null, 于是 game.finish 不叫醒任何人。
        data.put("agentTrigger", GomokuGame.nextMark(state) != null);
        return data;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
