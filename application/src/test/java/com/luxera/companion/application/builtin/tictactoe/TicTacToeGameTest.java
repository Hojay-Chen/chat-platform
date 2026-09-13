package com.luxera.companion.application.builtin.tictactoe;

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
 * 井字棋的规则是<em>可以被穷举验证</em>的那部分代码 —— 所以它被单独放在
 * {@link TicTacToeGame} 里, 由这个测试逐条钉住。
 *
 * <p>规则与编排分开写不只是审美: 处理器里混着 Spring、事务、资源写入, 那些东西测不了穷举;
 * 而"横竖斜三连判定"测得了。分开之后, 胜负判定有覆盖, 编排只有集成测试 —— 这是一个诚实的
 * 分工, 不是漏测。
 *
 * <p>这里刻意用真实的 {@code ObjectNode} 而不是 mock: 状态就是 JSON, 用假的 JSON 测一个操作
 * JSON 的函数, 测的是替身而不是代码。
 */
class TicTacToeGameTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ─────────────────────────── 开局 ───────────────────────────

    @Test
    void newStateSeatsTheCreatorAndLeavesTheOpponentSeatOpen() {
        ObjectNode state = TicTacToeGame.newState(mapper, "human-1", null);

        assertEquals("human-1", TicTacToeGame.seats(state).get(0));
        assertNull(TicTacToeGame.seats(state).get(1), "没指定对手时第二席空着等落座");
        assertEquals("X", TicTacToeGame.nextMark(state), "先手是 X");
        assertNull(TicTacToeGame.winner(state));
        assertFalse(TicTacToeGame.finished(state));
        assertEquals(0, state.path("moves").asInt());
        assertEquals(9, TicTacToeGame.board(state).size());
        for (int i = 0; i < TicTacToeGame.CELLS; i++) {
            assertNull(TicTacToeGame.cell(TicTacToeGame.board(state), i), "开局时第 " + i + " 格是空的");
        }
    }

    /** 指定了对手就直接入座 —— 于是"真人 + 数字人"的一局不必等对方先落一子才成为对局者。 */
    @Test
    void newStateSeatsTheNamedOpponentToo() {
        ObjectNode state = TicTacToeGame.newState(mapper, "human-1", "companion-1");

        assertEquals(List.of("human-1", "companion-1"), TicTacToeGame.seats(state));
    }

    /** 记号由<em>座位序号</em>推出, 与"坐着的是人还是 Agent"无关 —— 应用不得感知这两者之分。 */
    @Test
    void markComesFromTheSeatNotFromWhoIsSittingThere() {
        assertEquals("X", TicTacToeGame.markForSeat(0));
        assertEquals("O", TicTacToeGame.markForSeat(1));
    }

    // ─────────────────────────── 座位 ───────────────────────────

    @Test
    void seatLookupAndFreeSeat() {
        ObjectNode state = TicTacToeGame.newState(mapper, "human-1", null);

        assertEquals(0, TicTacToeGame.seatOf(state, "human-1"));
        assertEquals(-1, TicTacToeGame.seatOf(state, "stranger"));
        assertEquals(-1, TicTacToeGame.seatOf(state, null));
        assertEquals(1, TicTacToeGame.freeSeat(state));

        state.withArray("seats").set(1, mapper.getNodeFactory().textNode("companion-1"));
        assertEquals(-1, TicTacToeGame.freeSeat(state), "满座时没有空位");
    }

    // ─────────────────────────── 胜负 ───────────────────────────

    @Test
    void everyWinningLineIsRecognised() {
        int[][] lines = {
                {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
                {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
                {0, 4, 8}, {2, 4, 6}
        };
        for (int[] line : lines) {
            ArrayNode board = emptyBoard();
            for (int cell : line) {
                board.set(cell, mapper.getNodeFactory().textNode("O"));
            }
            assertEquals("O", TicTacToeGame.evaluate(board),
                    "第 " + line[0] + "-" + line[2] + " 条线应判和");
        }
    }

    @Test
    void mixedLinesAreNotAWin() {
        ArrayNode board = emptyBoard();
        board.set(0, mapper.getNodeFactory().textNode("X"));
        board.set(1, mapper.getNodeFactory().textNode("X"));
        board.set(2, mapper.getNodeFactory().textNode("O"));

        assertNull(TicTacToeGame.evaluate(board), "两子同色一子异色不是三连");
    }

    @Test
    void fullBoardWithoutALineIsADraw() {
        ArrayNode board = emptyBoard();
        String[] cells = {"X", "X", "O", "O", "O", "X", "X", "O", "X"};
        for (int i = 0; i < cells.length; i++) {
            board.set(i, mapper.getNodeFactory().textNode(cells[i]));
        }

        assertEquals(TicTacToeGame.DRAW, TicTacToeGame.evaluate(board));
    }

    /** 棋盘尺寸不对时返回"未终局"而不是抛异常: 一个坏掉的局面不该让整次动作 500。 */
    @Test
    void malformedBoardIsNotAWin() {
        assertNull(TicTacToeGame.evaluate(null));
        assertNull(TicTacToeGame.evaluate(mapper.createArrayNode()));
    }

    // ─────────────────────────── 轮次 ───────────────────────────

    /** {@code nextMark} 是 {@code data.agentTrigger} 的来源: 有人等着下一手, 才值得叫醒谁。 */
    @Test
    void nextMarkIsNullOnceTheGameIsOver() {
        ObjectNode state = TicTacToeGame.newState(mapper, "human-1", "companion-1");
        assertEquals("X", TicTacToeGame.nextMark(state));

        state.put("turn", "O");
        assertEquals("O", TicTacToeGame.nextMark(state));

        state.put("winner", "O");
        assertNull(TicTacToeGame.nextMark(state), "终局之后没有人在等下一手");
        assertTrue(TicTacToeGame.finished(state));
    }

    @Test
    void finishedIsDrivenByWinnerIncludingDraw() {
        ObjectNode state = TicTacToeGame.newState(mapper, "human-1", null);
        state.put("winner", TicTacToeGame.DRAW);
        assertTrue(TicTacToeGame.finished(state), "平局也是终局");
    }

    private ArrayNode emptyBoard() {
        ArrayNode board = mapper.createArrayNode();
        for (int i = 0; i < TicTacToeGame.CELLS; i++) {
            board.addNull();
        }
        return board;
    }
}
