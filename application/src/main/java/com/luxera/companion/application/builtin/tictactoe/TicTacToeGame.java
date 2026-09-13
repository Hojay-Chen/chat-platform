package com.luxera.companion.application.builtin.tictactoe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 井字棋的纯规则 —— 没有 Spring、没有 JPA、没有状态。
 *
 * <p>之所以把它从处理器里分出来: 这些函数是<em>可以被穷举验证</em>的(九宫格只有 3^9 个局面),
 * 而处理器不能。规则和编排分开, 才谈得上"胜负判定有测试覆盖"。
 *
 * <p><b>棋盘状态是资源, 不是表。</b>整盘棋就存在 {@code resource.state_json} 里
 * ({@code game://session/{id}}), 在动作的同一个事务内经 CAS 写入。于是"两个 principal 抢
 * 同一步棋"这件事由 {@code state_version} 兜住 —— 输的那个拿到干干净净的 409, 而不是丢一个子。
 * 整个 {@code GameSession} 实体因此不存在了。
 *
 * <p><b>座位与记号是分开的。</b>{@code seats[0]} 执 X, {@code seats[1]} 执 O; 记号由
 * <em>座位序号</em>推出, 与"坐着的是人还是 Agent"无关。这是"应用不得感知 Human / Agent 分支"
 * 这条约束在代码里的落点 —— 判断"该谁走"时根本不去问调用方是什么。
 */
public final class TicTacToeGame {

    public static final int CELLS = 9;
    public static final int SEATS = 2;
    public static final String MARK_X = "X";
    public static final String MARK_O = "O";
    public static final String DRAW = "DRAW";

    private TicTacToeGame() {
    }

    /** 空棋盘: 座位一(执 X)已有人, 座位二空着等对手落座。 */
    public static ObjectNode newState(ObjectMapper mapper, String firstSeat, String secondSeat) {
        ObjectNode state = mapper.createObjectNode();
        ArrayNode board = state.putArray("board");
        for (int i = 0; i < CELLS; i++) {
            board.addNull();
        }
        ArrayNode seats = state.putArray("seats");
        seats.add(firstSeat);
        if (secondSeat == null) {
            seats.addNull();
        } else {
            seats.add(secondSeat);
        }
        state.put("turn", MARK_X);
        state.putNull("winner");
        state.put("moves", 0);
        return state;
    }

    public static ArrayNode board(JsonNode state) {
        return state != null && state.path("board").isArray()
                ? (ArrayNode) state.get("board") : null;
    }

    public static List<String> seats(JsonNode state) {
        if (state == null || !state.path("seats").isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport
                .stream(state.get("seats").spliterator(), false)
                .map(node -> node == null || node.isNull() ? null : node.asText())
                .toList();
    }

    /** 记号 ↔ 座位序号: 0 → X, 1 → O。顺序即规则。 */
    public static String markForSeat(int seat) {
        return seat == 0 ? MARK_X : MARK_O;
    }

    public static String winner(JsonNode state) {
        JsonNode winner = state == null ? null : state.get("winner");
        return winner == null || winner.isNull() ? null : winner.asText();
    }

    public static boolean finished(JsonNode state) {
        return winner(state) != null;
    }

    /** 该 principal 的座位序号; 没落座返回 -1。 */
    public static int seatOf(JsonNode state, String principalId) {
        if (principalId == null) {
            return -1;
        }
        List<String> seats = seats(state);
        for (int i = 0; i < seats.size(); i++) {
            if (principalId.equals(seats.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 第一个空座位序号; 满座返回 -1。 */
    public static int freeSeat(JsonNode state) {
        List<String> seats = seats(state);
        for (int i = 0; i < SEATS; i++) {
            if (i >= seats.size() || seats.get(i) == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从棋盘算出胜负。{@code null} = 未终局, {@link #DRAW} = 平局。
     * 三条横、三条竖、两条对角线 —— 写死比循环更好读, 也更好验证。
     */
    public static String evaluate(ArrayNode board) {
        if (board == null || board.size() != CELLS) {
            return null;
        }
        int[][] lines = {
                {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
                {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
                {0, 4, 8}, {2, 4, 6}
        };
        for (int[] line : lines) {
            String a = cell(board, line[0]);
            if (a != null && a.equals(cell(board, line[1])) && a.equals(cell(board, line[2]))) {
                return a;
            }
        }
        for (int i = 0; i < CELLS; i++) {
            if (cell(board, i) == null) {
                return null;
            }
        }
        return DRAW;
    }

    public static String cell(ArrayNode board, int index) {
        if (board == null || index < 0 || index >= board.size()) {
            return null;
        }
        JsonNode node = board.get(index);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 在此局面下, 下一个该走的记号。终局返回 {@code null}。
     *
     * <p>{@code events[].data.agentTrigger} 就取自它是否为空 —— "有人在等下一手", 一个不含
     * "谁是 Agent" 的陈述。
     */
    public static String nextMark(JsonNode state) {
        if (finished(state)) {
            return null;
        }
        JsonNode turn = state.path("turn");
        return turn.isTextual() ? turn.asText() : MARK_X;
    }
}
