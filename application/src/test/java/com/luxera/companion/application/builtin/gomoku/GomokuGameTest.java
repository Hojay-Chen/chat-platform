package com.luxera.companion.application.builtin.gomoku;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五子棋的规则测试 —— 与 {@link com.luxera.companion.application.builtin.tictactoe.TicTacToeGameTest}
 * 刻意写成同构的。
 *
 * <p><b>这个测试有一半的意义在"没测什么"上。</b>它不测 handler、不测 manifest、不测事件 ——
 * 那些东西井字棋已经证明过一遍了, 而五子棋存在的价值恰恰是"它<em>不需要</em>新的那套东西"。
 * 这里只钉住五子棋独有的部分: 225 格棋盘、四个方向的判胜、以及"五连或更长"的判定。
 *
 * <p>判胜方向最容易漏的是<em>反对角线</em>(右上)。横、竖、主对角线三个方向写对而第四个写错,
 * 是一个只在特定棋局才暴露的 bug, 所以这里对四条方向各给一个用例。
 */
class GomokuGameTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ─────────────────────────── 开局与坐标 ───────────────────────────

    @Test
    void newStateIsAnEmptyFifteenByFifteenBoard() {
        ObjectNode state = GomokuGame.newState(mapper, "human-1", null);

        assertEquals(225, GomokuGame.board(state).size());
        assertEquals(15, state.path("size").asInt());
        assertEquals("human-1", GomokuGame.seats(state).get(0));
        assertNull(GomokuGame.seats(state).get(1), "没指定对手时第二席空着等落座");
        assertEquals("X", GomokuGame.nextMark(state));
        assertNull(GomokuGame.winner(state));
        assertFalse(GomokuGame.finished(state));
        for (int i = 0; i < GomokuGame.CELLS; i++) {
            assertNull(GomokuGame.cell(GomokuGame.board(state), i), "开局时第 " + i + " 格是空的");
        }
    }

    /** 位置 ↔ (行,列) 的换算是对外契约的一部分: manifest 里对用户说的是"格子序号 = 行 × 15 + 列"。 */
    @Test
    void positionIsRowTimesFifteenPlusColumn() {
        assertEquals(0, GomokuGame.index(0, 0));
        assertEquals(15, GomokuGame.index(1, 0));
        assertEquals(16, GomokuGame.index(1, 1));
        assertEquals(224, GomokuGame.index(14, 14));

        assertEquals(7, GomokuGame.rowOf(112));
        assertEquals(7, GomokuGame.colOf(112), "第 112 格是正中央的天元");
        assertEquals(14, GomokuGame.rowOf(224));
        assertEquals(14, GomokuGame.colOf(224));
    }

    @Test
    void marksComeFromTheSeatNotFromWhoIsSittingThere() {
        assertEquals("X", GomokuGame.markForSeat(0));
        assertEquals("O", GomokuGame.markForSeat(1));
    }

    @Test
    void seatLookupAndFreeSeat() {
        ObjectNode state = GomokuGame.newState(mapper, "human-1", null);

        assertEquals(0, GomokuGame.seatOf(state, "human-1"));
        assertEquals(-1, GomokuGame.seatOf(state, "stranger"));
        assertEquals(-1, GomokuGame.seatOf(state, null));
        assertEquals(1, GomokuGame.freeSeat(state));

        state.withArray("seats").set(1, mapper.getNodeFactory().textNode("companion-1"));
        assertEquals(-1, GomokuGame.freeSeat(state), "满座时没有空位");
    }

    // ─────────────────────────── 判胜：四个方向 ───────────────────────────

    @Test
    void fiveInARowHorizontallyWins() {
        ArrayNode board = board();

        for (int col = 3; col < 8; col++) {
            board.set(GomokuGame.index(7, col), text("O"));
        }

        assertEquals("O", GomokuGame.evaluate(board));
    }

    @Test
    void fiveInARowVerticallyWins() {
        ArrayNode board = board();

        for (int row = 2; row < 7; row++) {
            board.set(GomokuGame.index(row, 9), text("X"));
        }

        assertEquals("X", GomokuGame.evaluate(board));
    }

    @Test
    void fiveInARowOnTheMainDiagonalWins() {
        ArrayNode board = board();

        for (int i = 0; i < GomokuGame.WIN_LENGTH; i++) {
            board.set(GomokuGame.index(i, i), text("O"));
        }

        assertEquals("O", GomokuGame.evaluate(board));
    }

    /** 右上方向 —— 四个方向里最容易写漏的那一个。 */
    @Test
    void fiveInARowOnTheAntiDiagonalWins() {
        ArrayNode board = board();

        for (int i = 0; i < GomokuGame.WIN_LENGTH; i++) {
            board.set(GomokuGame.index(10 - i, 4 + i), text("X"));
        }

        assertEquals("X", GomokuGame.evaluate(board));
    }

    /** 一条线横跨棋盘边界时不得绕回来数 —— 边界检查漏掉会让 `(0,14)` 和 `(1,0)` 连成一线。 */
    @Test
    void aRunDoesNotWrapAroundTheBoardEdge() {
        ArrayNode board = board();

        board.set(GomokuGame.index(0, 13), text("O"));
        board.set(GomokuGame.index(0, 14), text("O"));
        board.set(GomokuGame.index(1, 0), text("O"));
        board.set(GomokuGame.index(1, 1), text("O"));
        board.set(GomokuGame.index(1, 2), text("O"));

        assertNull(GomokuGame.evaluate(board), "换行处的 5 子不是一条线");
    }

    // ─────────────────────────── 判胜：边角情形 ───────────────────────────

    /** 规则是 freestyle: 六连也算赢(不判禁手)。改规则只该改一个方法。 */
    @Test
    void sixInARowIsStillAWin() {
        ArrayNode board = board();

        for (int col = 0; col < 6; col++) {
            board.set(GomokuGame.index(3, col), text("O"));
        }

        assertEquals("O", GomokuGame.evaluate(board));
    }

    @Test
    void fourInARowIsNotAWin() {
        ArrayNode board = board();

        for (int col = 5; col < 9; col++) {
            board.set(GomokuGame.index(11, col), text("O"));
        }

        assertNull(GomokuGame.evaluate(board));
    }

    /** 四子同色被一子异色打断 —— 连续计数必须是"打断即归零", 不是"总共数到几个"。 */
    @Test
    void anInterruptedRunIsNotAWin() {
        ArrayNode board = board();

        board.set(GomokuGame.index(6, 2), text("X"));
        board.set(GomokuGame.index(6, 3), text("X"));
        board.set(GomokuGame.index(6, 4), text("O"));
        board.set(GomokuGame.index(6, 5), text("X"));
        board.set(GomokuGame.index(6, 6), text("X"));
        board.set(GomokuGame.index(6, 7), text("X"));

        assertNull(GomokuGame.evaluate(board));
    }

    @Test
    void fullBoardWithoutFiveInARowIsADraw() {
        ArrayNode board = board();
        // 取值 (row + 2*col) mod 4 < 2 —— 四个方向上相邻两格的差值分别是 2 / 1 / 3 / -1,
        // 都同余于 ±1 (mod 4), 所以任何一个方向的连续同色都不超过 2 格, 必然填满而无五连。
        for (int row = 0; row < GomokuGame.SIZE; row++) {
            for (int col = 0; col < GomokuGame.SIZE; col++) {
                board.set(GomokuGame.index(row, col), text((row + 2 * col) % 4 < 2 ? "X" : "O"));
            }
        }

        assertEquals(GomokuGame.DRAW, GomokuGame.evaluate(board));
    }

    /** 棋盘尺寸不对时返回"未终局"而不是抛异常 —— 坏掉的局面不该让整次动作 500。 */
    @Test
    void malformedBoardIsNotAWin() {
        assertNull(GomokuGame.evaluate(null));
        assertNull(GomokuGame.evaluate(mapper.createArrayNode()));
        assertNull(GomokuGame.evaluate(mapper.createArrayNode().add("X")));
    }

    // ─────────────────────────── 轮次 ───────────────────────────

    @Test
    void nextMarkIsNullOnceTheGameIsOver() {
        ObjectNode state = GomokuGame.newState(mapper, "human-1", "companion-1");
        assertEquals("X", GomokuGame.nextMark(state));

        state.put("turn", "O");
        assertEquals("O", GomokuGame.nextMark(state));

        state.put("winner", "O");
        assertNull(GomokuGame.nextMark(state), "终局之后没有人在等下一手");
        assertTrue(GomokuGame.finished(state));
    }

    @Test
    void finishedIsDrivenByWinnerIncludingDraw() {
        ObjectNode state = GomokuGame.newState(mapper, "human-1", null);
        state.put("winner", GomokuGame.DRAW);
        assertTrue(GomokuGame.finished(state), "平局也是终局");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private ArrayNode board() {
        ArrayNode board = mapper.createArrayNode();
        for (int i = 0; i < GomokuGame.CELLS; i++) {
            board.addNull();
        }
        return board;
    }

    private com.fasterxml.jackson.databind.node.TextNode text(String value) {
        return mapper.getNodeFactory().textNode(value);
    }

    /** seats 的读取要能吃下 null 元素 —— 开局第二席就是 null。 */
    @Test
    void seatsToleratesNullsAndMissingNodes() {
        assertEquals(List.of(), GomokuGame.seats(null));
        assertEquals(List.of(), GomokuGame.seats(mapper.createObjectNode()));

        ObjectNode state = GomokuGame.newState(mapper, "human-1", "companion-1");
        assertEquals(List.of("human-1", "companion-1"), GomokuGame.seats(state));
    }
}
