package com.luxera.companion.application.builtin.gomoku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 五子棋的纯规则 —— 没有 Spring、没有 JPA、没有状态。与 {@code TicTacToeGame} 同构。
 *
 * <p><b>它存在的意义不是"多一个游戏", 而是把"加一个游戏要动多少地方"变成一个可检验的问题。</b>
 * 加它的时候改的文件只有三个: 本类、{@link GomokuApplication}、以及它自己的
 * {@code application-manifest.json}。数字人侧一行没动, {@code AgentRuntime} /
 * {@code AgentApplicationFlow} / 感知 / 认知 / 决策全都不知道五子棋的存在 —— 这是 LAP v1
 * 要证明的那件事, 而证明它的方式就是真的加一个。
 *
 * <p><b>规则是"五连或更长即胜"</b>(freestyle)。换一种规则(禁手、恰好五连)只需要改
 * {@link #evaluate} —— 棋盘与座位那套与井字棋完全一样, 因为它们描述的是"两个 principal
 * 轮流在一个网格上下子", 与棋种无关。
 *
 * <p>棋盘 {@code 15×15 = 225} 格, {@code position = row * 15 + col}, 行列都从 0 起。
 */
public final class GomokuGame {

    public static final int SIZE = 15;
    public static final int CELLS = SIZE * SIZE;
    public static final int SEATS = 2;
    public static final int WIN_LENGTH = 5;
    public static final String MARK_X = "X";
    public static final String MARK_O = "O";
    public static final String DRAW = "DRAW";

    /** 四个方向: 横、竖、右下、右上。反方向由"从每一格都试一遍"覆盖。 */
    private static final int[][] DIRECTIONS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    private GomokuGame() {
    }

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
        state.put("size", SIZE);
        return state;
    }

    public static int index(int row, int col) {
        return row * SIZE + col;
    }

    public static int rowOf(int position) {
        return position / SIZE;
    }

    public static int colOf(int position) {
        return position % SIZE;
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

    /** 记号 ↔ 座位序号: 0 → X, 1 → O。与井字棋同一条规则, 因为规则属于"轮流下子"而不是某个棋种。 */
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

    public static int freeSeat(JsonNode state) {
        List<String> seats = seats(state);
        for (int i = 0; i < SEATS; i++) {
            if (i >= seats.size() || seats.get(i) == null) {
                return i;
            }
        }
        return -1;
    }

    public static String cell(ArrayNode board, int position) {
        if (board == null || position < 0 || position >= board.size()) {
            return null;
        }
        JsonNode node = board.get(position);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 从棋盘算出胜负。{@code null} = 未终局, {@link #DRAW} = 和棋(棋盘填满仍无五连)。
     *
     * <p>对每一格、每一个方向数一遍连续同色。看起来比"只看最后一手"笨, 但它是<em>纯函数</em>:
     * 输入棋盘输出结论, 不依赖"刚才下在哪"这个外部记忆。能这么写是因为 225 格 × 4 个方向
     * 的扫描代价可以忽略, 而"胜负判定不看历史"省掉了一整类难查的 bug。
     */
    public static String evaluate(ArrayNode board) {
        if (board == null || board.size() != CELLS) {
            return null;
        }
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                String mark = cell(board, index(row, col));
                if (mark == null) {
                    continue;
                }
                for (int[] direction : DIRECTIONS) {
                    if (runFrom(board, row, col, direction[0], direction[1], mark) >= WIN_LENGTH) {
                        return mark;
                    }
                }
            }
        }
        for (int i = 0; i < CELLS; i++) {
            if (cell(board, i) == null) {
                return null;
            }
        }
        return DRAW;
    }

    /** 从 (row,col) 沿一个方向数连续同色子。起点那格计入。 */
    private static int runFrom(ArrayNode board, int row, int col, int dr, int dc, String mark) {
        int count = 0;
        while (row >= 0 && row < SIZE && col >= 0 && col < SIZE
                && mark.equals(cell(board, index(row, col)))) {
            count++;
            row += dr;
            col += dc;
        }
        return count;
    }

    /**
     * 在此局面下, 下一个该走的记号。终局返回 {@code null}。
     *
     * <p>{@code events[].data.agentTrigger} 就取自它是否为空 —— 与井字棋用的是同一句话:
     * "有人在等下一手"。
     */
    public static String nextMark(JsonNode state) {
        if (finished(state)) {
            return null;
        }
        JsonNode turn = state.path("turn");
        return turn.isTextual() ? turn.asText() : MARK_X;
    }
}
