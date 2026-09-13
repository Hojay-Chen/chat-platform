package com.luxera.companion.application.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.ResourceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发现链的后两级: <b>选中应用 → 它的 action → 执行</b>, 以及这条链最容易塌掉的地方 ——
 * 两个应用声明了<em>同名</em>的 action。
 *
 * <p>{@code com.luxera.tictactoe} 与 {@code com.luxera.gomoku} 都有 {@code game.make_move},
 * 而且都是同一份代码注册进同一个 {@code ActionHandlerRegistry}。如果注册表的键是
 * {@code String actionId}(这是最顺手的写法), 后注册的那个会<em>覆盖</em>先注册的那个,
 * 于是下五子棋会去改井字棋的棋盘 —— 一个安静到几乎测不出来的错误: 两边都不报错,
 * 只是一个游戏的棋步长到了另一个游戏上。
 *
 * <p>所以这个测试里最要紧的两条是 {@link #theSameActionIdExistsInTwoApplications()} 与
 * {@link #theTwoGamesKeepTheirOwnBoards()}。它们合起来说的是一句话:
 * <b>handler 的键是 {@code (applicationId, version, actionId)}, 不是 {@code actionId}。</b>
 *
 * <p>第二个主题是 <b>{@code agentHint} 必须从 manifest 一路走到发现结果</b>。井字棋与五子棋
 * 的 {@code game.make_move} 提示写得不一样(一个是 3×3 的九个格子, 一个是 15×15 的 225 格),
 * 而这两段文字<em>曾经</em>硬编码在数字人的 {@code AgentRuntime} 里。断言它们不同, 就是在断言
 * 那段策略文本真的搬到了应用一侧 —— 否则"加第二个游戏"这一轮什么也没证明。
 */
@ActiveProfiles("test")
@SpringBootTest
class ApplicationDiscoveryServiceTest {

    private static final String TICTACTOE = "com.luxera.tictactoe";
    private static final String GOMOKU = "com.luxera.gomoku";
    private static final String REMINDER = "com.luxera.reminder";

    private static final String CREATE = "game.create";
    private static final String MAKE_MOVE = "game.make_move";

    @Autowired
    ActionGateway gateway;

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    ObjectMapper objectMapper;

    // ─────────────────────────── 选中应用 → 它的 action ───────────────────────────

    @Test
    void actionsOfAnApplicationComeFromItsOwnManifest() {
        assertEquals(List.of(CREATE, "game.state", MAKE_MOVE, "game.surrender"), actionIds(TICTACTOE));
        assertEquals(List.of(CREATE, "game.state", MAKE_MOVE, "game.surrender"), actionIds(GOMOKU));
        assertEquals(List.of("reminder.create", "reminder.update", "reminder.complete",
                "reminder.cancel", "reminder.list"), actionIds(REMINDER));
    }

    /** 一个应用只交出自己的 action —— 提醒应用不该看到"落子"。 */
    @Test
    void anApplicationOnlyExposesItsOwnActions() {
        assertFalse(actionIds(REMINDER).contains(MAKE_MOVE));
        assertFalse(actionIds(TICTACTOE).contains("reminder.create"));
    }

    @Test
    void anUnknownApplicationExposesNoActions() {
        assertEquals(List.of(), gateway.actionsOf("com.luxera.nonexistent"));
        assertEquals(List.of(), gateway.actionsOf(null));
    }

    /** 发现结果里每一件 action 都要能被执行 —— {@code ManifestRegistrar} 在发布时校验过这一条。 */
    @Test
    void everyDiscoveredActionCarriesWhatACallerNeeds() {
        for (String appId : List.of(TICTACTOE, GOMOKU, REMINDER)) {
            for (ActionSpec spec : gateway.actionsOf(appId)) {
                assertEquals(appId, spec.applicationId(), "action 必须自报属于哪个应用");
                assertNotNull(spec.permissionLevel(), appId + " 的 " + spec.actionId() + " 缺 permission");
                assertNotNull(spec.riskLevel(), appId + " 的 " + spec.actionId() + " 缺 risk");
                assertNotNull(spec.attention(), appId + " 的 " + spec.actionId() + " 缺 attention");
                assertNotNull(spec.description(), appId + " 的 " + spec.actionId() + " 缺描述");
                assertNotNull(spec.capabilityId(), appId + " 的 " + spec.actionId() + " 缺 capability");
            }
        }
    }

    // ─────────────────────────── 同名 action 的隔离 ───────────────────────────

    @Test
    void theSameActionIdExistsInTwoApplications() {
        assertTrue(actionIds(TICTACTOE).contains(MAKE_MOVE));
        assertTrue(actionIds(GOMOKU).contains(MAKE_MOVE));
        assertNotEquals(TICTACTOE, GOMOKU, "前提是两个不同的应用");
    }

    /**
     * {@code game.make_move} 被两个应用声明, 所以<em>只看动作 id 是选不出应用的</em>。
     * 消歧靠 target: 两个棋局各用各的 URI 方案, 谁拥有这个 URI, 这一步就落在谁那里。
     *
     * <p>这条断言同时钉住了两个应用的资源模板 —— 如果哪天有人把五子棋的模板也改成
     * {@code game://session/{sessionId}}, 这里会立刻变成 {@code AMBIGUOUS_ACTION},
     * 而不是"偶尔落到对方棋盘上"。
     */
    @Test
    void theTargetUriDecidesWhichGameGetsTheMove() {
        Human alice = newHuman();

        Roll ticTacToe = newGame(TICTACTOE, alice);
        Roll gomoku = newGame(GOMOKU, alice);

        assertTrue(ticTacToe.uri().startsWith("game://session/"), "井字棋的 URI: " + ticTacToe.uri());
        assertTrue(gomoku.uri().startsWith("gomoku://match/"), "五子棋的 URI: " + gomoku.uri());

        assertEquals(ActionStatus.SUCCESS, move(ticTacToe, 0).status());
        assertEquals(ActionStatus.SUCCESS, move(gomoku, 0).status());

        assertEquals(TICTACTOE, resourceOwner(ticTacToe.uri()));
        assertEquals(GOMOKU, resourceOwner(gomoku.uri()));
    }

    /**
     * 两条同名 action 的 {@code agentHint} 必须<em>不同</em> —— 3×3 与 15×15 的落子策略不可能是
     * 同一段文字。相同的提示意味着策略又回到了某个共同的地方(比如 DH), 而不是各写在各的 manifest。
     */
    @Test
    void theTwoGamesGiveTheirOwnMoveHints() {
        String ticTacToe = hintOf(TICTACTOE, MAKE_MOVE);
        String gomoku = hintOf(GOMOKU, MAKE_MOVE);

        assertNotNull(ticTacToe);
        assertNotNull(gomoku);
        assertNotEquals(ticTacToe, gomoku, "两个棋种的落子提示必须各自写在 manifest 里");
        assertTrue(ticTacToe.contains("0..8") || ticTacToe.contains("3 4 5"),
                "井字棋的提示应当说清 3×3 的九个格子: " + ticTacToe);
        assertTrue(gomoku.contains("15"), "五子棋的提示应当说清 15×15: " + gomoku);
    }

    /**
     * <b>本类最重要的一条。</b>在两个应用里各走一步, 两个棋盘必须<em>各自</em>变化。
     *
     * <p>如果 handler 注册表的键少了 {@code applicationId}, 两个应用会共用同一个处理器实例,
     * 于是其中一张棋盘永远不动 —— 而两边都返回 SUCCESS。
     */
    @Test
    void theTwoGamesKeepTheirOwnBoards() {
        Human alice = newHuman();

        Roll ticTacToe = newGame(TICTACTOE, alice);
        Roll gomoku = newGame(GOMOKU, alice);

        assertEquals(ActionStatus.SUCCESS, move(ticTacToe, 4).status(), "井字棋第 4 格");
        assertEquals(ActionStatus.SUCCESS, move(gomoku, 112).status(), "五子棋天元");

        JsonNode ticBoard = board(ticTacToe);
        JsonNode goBoard = board(gomoku);

        assertEquals(9, ticBoard.size(), "井字棋是 3×3");
        assertEquals(225, goBoard.size(), "五子棋是 15×15");
        assertEquals("X", ticBoard.get(4).asText(), "井字棋的第 4 格落了子");
        assertEquals("X", goBoard.get(112).asText(), "五子棋的第 112 格落了子");
        // 交叉污染的两条最直接的证据:
        assertTrue(ticBoard.get(112) == null, "井字棋没有第 112 格 —— 落错棋盘会让它越界");
        assertTrue(goBoard.get(4).isNull(), "五子棋的第 4 格不该因为井字棋落子而变化: " + goBoard.get(4));
    }

    /**
     * 同一个位置号在两个应用里的含义完全不同: 第 9 格在井字棋上越界(只有 0..8), 在五子棋上
     * 却是第二行的第一格。<b>合法性由应用自己判, 平台不替它判</b> —— 平台连棋盘有多大都不知道。
     *
     * <p>注意状态是 {@code FAILED} 而不是 {@code INVALID_ARGUMENT}: 后者是<em>平台</em>对请求形状的
     * 裁决(缺 target、动作不存在), 前者是<em>应用</em>对内容的裁决("这个位置在我的棋盘上不存在")。
     * 两者都带错误码, 但一个是"你这条请求不合法", 另一个是"你的请求合法, 但这件事办不成"。
     */
    @Test
    void theSamePositionNumberMeansDifferentThingsInTheTwoGames() {
        Human alice = newHuman();

        Roll ticTacToe = newGame(TICTACTOE, alice);
        Roll gomoku = newGame(GOMOKU, alice);

        ActionResponse refused = move(ticTacToe, 9);
        assertEquals(ActionStatus.FAILED, refused.status(), "井字棋没有第 9 格");
        assertEquals("INVALID_POSITION", refused.error().code());
        assertEquals(ActionStatus.SUCCESS, move(gomoku, 9).status(), "五子棋的第 9 格是合法的");
    }

    /** 五子棋的位置范围是 0..224 —— 边界值由应用自己守, 平台不替它判断。 */
    @Test
    void eachGameGuardsItsOwnBoundaries() {
        Human alice = newHuman();
        Roll gomoku = newGame(GOMOKU, alice);

        for (int bad : new int[]{-1, 225, 1000}) {
            ActionResponse response = move(gomoku, bad);
            assertEquals("INVALID_POSITION", response.error().code(), "第 " + bad + " 格越界");
        }
        assertEquals(ActionStatus.SUCCESS, move(gomoku, 224).status(), "第 224 格是最后一格, 合法");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private record Human(String principalId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
        }
    }

    private record Roll(Human human, String uri, ActionResponse create) {
    }

    /**
     * 一个全新的人。v1 这里要"给他装上某个应用", v2 不需要 —— 装这件事没有对应物了。
     *
     * <p>他成为参与者的时刻是 {@link #newGame} 里开一局的时候(开局的人就是 OWNER)。于是"同一个人
     * 在两个应用里各开一局"这件事在 v2 里自然成立, 不需要先给他两个 installation。
     */
    private Human newHuman() {
        return new Human(UUID.randomUUID().toString());
    }

    private Roll newGame(String applicationId, Human human) {
        ApplicationSessionRecord session = sessionService.launch(applicationId, human.principal());
        String uri = uriFor(applicationId, session.getId());
        ActionResponse create = gateway.execute(ActionRequest.of(CREATE, uri, null), ctx(human));
        return new Roll(human, uri, create);
    }

    /**
     * 两个棋局各用各的 URI 方案 —— 这不是装饰。{@code game.make_move} 被两个应用同时声明,
     * 唯一能消歧的东西就是 target: {@code game://session/…} 只匹配井字棋,
     * {@code gomoku://match/…} 只匹配五子棋。见 {@link #theTargetUriDecidesWhichGameGetsTheMove()}。
     */
    private static String uriFor(String applicationId, String sessionId) {
        return GOMOKU.equals(applicationId)
                ? "gomoku://match/" + sessionId
                : "game://session/" + sessionId;
    }

    /**
     * 落子。<b>每次都用一个全新的幂等键</b> —— 这一步的语义是"一次新的尝试", 不是"重放上一次"。
     * 用派生键会让同一局面下的第二次落子被幂等层短路, 于是测出来的东西跟想测的不是一回事。
     */
    private ActionResponse move(Roll roll, int position) {
        ObjectNode input = objectMapper.createObjectNode().put("position", position);
        return gateway.execute(new ActionRequest(MAKE_MOVE, roll.uri, input, null),
                roll.human().principal(), "move-" + UUID.randomUUID()).response();
    }

    private JsonNode board(Roll roll) {
        return view(roll).state().path("board");
    }

    private String resourceOwner(String uri) {
        return gateway.read(uri).orElseThrow().applicationId();
    }

    private ResourceView view(Roll roll) {
        return gateway.read(roll.uri).orElseThrow(() -> new AssertionError("资源应当存在: " + roll.uri()));
    }

    private List<String> actionIds(String applicationId) {
        return gateway.actionsOf(applicationId).stream().map(ActionSpec::actionId).toList();
    }

    private String hintOf(String applicationId, String actionId) {
        return gateway.actionsOf(applicationId).stream()
                .filter(a -> actionId.equals(a.actionId()))
                .map(ActionSpec::agentHint)
                .findFirst().orElse(null);
    }

    private static InvocationContext ctx(Human human) {
        return human.principal().toInvocationContext();
    }
}
