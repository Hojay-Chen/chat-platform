package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ActionInvocationRepository;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关上走一遍完整的七道 —— 真人走的路径和 Agent 走的路径是<em>同一条</em>, 所以这个类里
 * 没有"Agent 专用"的用例, 只有"换个 principal"的用例。那正是 LAP 要说的事。
 *
 * <p>三条断言值得单独指出:
 *
 * <ul>
 *   <li><b>READ 从不产生 invocation。</b>方案原文给所有动作都要求幂等键, 而现有实现里
 *       {@code game.state} 也会带 key。真按那样落地, 同一个房间第二次读棋局会<em>重放</em>
 *       第一次的旧棋盘。断言"读之后行数不变"就是这条修正的保险丝。</li>
 *   <li><b>"该谁走"由应用回答, 不由平台判断。</b>{@code pendingActions} 在轮到 O 时给 O 返回
 *       动作、给 X 返回空 —— 平台不知道任何关于棋的规则, 它只是把问题转给应用。</li>
 *   <li><b>在场的人能读, 但"是不是对局者"仍然是应用说了算。</b>权限管的是"能不能调这个动作",
 *       对局资格是应用自己的规则, 两者在不同的层, 断言里分开看:v2 里一个被邀请进来的人能读、
 *       也拿得到写权限, 却仍然会被应用以 {@code NOT_A_PLAYER} 拒掉。</li>
 *   <li><b>不在这一局里的人连读都不行。</b>这是 v2 相对 v1 收紧的一处: v1 的第一维是"装没装",
 *       于是任何装过井字棋的人都能读别人的棋局; 现在是"在不在这一局里"。{@code NOT_A_PARTICIPANT}
 *       就是这条收窄的可见形状。</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest
class ActionGatewayTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String CREATE = "game.create";
    private static final String STATE = "game.state";
    private static final String MAKE_MOVE = "game.make_move";

    @Autowired
    ActionGateway gateway;

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    ParticipantService participantService;

    @Autowired
    ActionInvocationRepository invocations;

    @Autowired
    ObjectMapper objectMapper;

    // ─────────────────────────── 发现 ───────────────────────────

    @Test
    void capabilitiesApplicationsAndActionsAreAllDiscoverable() {
        List<CapabilityView> capabilities = gateway.capabilities();
        assertTrue(capabilities.stream().anyMatch(c -> "game.play".equals(c.capabilityId())),
                "能力目录里应当有 game.play: " + capabilities);

        List<ApplicationView> candidates = gateway.applicationsFor("game.play");
        assertTrue(candidates.stream().anyMatch(a -> APP_ID.equals(a.applicationId())),
                "game.play 下应当有井字棋");

        List<String> actionIds = gateway.actionsOf(APP_ID).stream().map(ActionSpec::actionId).toList();
        assertEquals(List.of(CREATE, STATE, MAKE_MOVE, "game.surrender"), actionIds,
                "发现链给出的动作必须与 manifest 逐条一致");

        // agentHint 是应用的作者写给 LLM 的, 必须一路带到发现结果里 —— 否则策略又得回到 DH
        ActionSpec makeMove = gateway.actionsOf(APP_ID).stream()
                .filter(a -> MAKE_MOVE.equals(a.actionId())).findFirst().orElseThrow();
        assertNotNull(makeMove.agentHint());
        assertTrue(makeMove.agentHint().contains("中心"), "落子提示应当来自 manifest: " + makeMove.agentHint());
        assertNotNull(makeMove.inputSchema(), "输入 schema 要给到调用方");
    }

    /** 读到的资源要带上 manifest 里声明的 {@code agentHint} —— 数字人靠它读局面。 */
    @Test
    void readingAResourceCarriesTheManifestsAgentHint() {
        Board board = newBoard();

        ResourceView view = gateway.read(board.uri).orElseThrow();

        assertNotNull(view.agentHint());
        assertTrue(view.agentHint().contains("state.board"), "提示应当说明怎么读局面: " + view.agentHint());
        assertEquals(1L, view.version());
    }

    // ─────────────────────────── 开一局, 读一局 ───────────────────────────

    @Test
    void createThenReadTheSameResource() {
        Human alice = newHuman();
        Board board = newBoard(alice);

        assertEquals(ActionStatus.SUCCESS, board.create.status());
        assertEquals(board.uri, board.create.resource().uri());

        ActionResponse state = gateway.execute(
                ActionRequest.of(STATE, board.uri, null), ctx(alice));
        assertEquals(ActionStatus.SUCCESS, state.status());
        assertEquals(9, boardRow(board.uri).state().path("board").size(),
                "读完应当看到一整张空棋盘: " + boardRow(board.uri).state());
    }

    /** <b>READ 从不写 invocation。</b>否则"读一眼棋局"会被幂等层重放成旧棋盘。 */
    @Test
    void readActionsNeverRecordAnInvocation() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        long before = invocationCount(alice.principalId);

        for (int i = 0; i < 3; i++) {
            assertEquals(ActionStatus.SUCCESS,
                    gateway.execute(ActionRequest.of(STATE, board.uri, null), ctx(alice)).status());
        }

        assertEquals(before, invocationCount(alice.principalId),
                "读动作不该在 action_invocation 里留下任何一行");
    }

    // ─────────────────────────── 幂等 ───────────────────────────

    @Test
    void writeWithoutAnIdempotencyKeyIsRejected() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        long before = invocationCount(alice.principalId);   // 开局那一步本身占一行

        ActionGateway.ActionExecution execution = gateway.execute(
                move(board, 4), alice.principal(), null);

        assertEquals(ActionStatus.IDEMPOTENCY_KEY_REQUIRED, execution.response().status());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", execution.response().error().code());
        assertEquals(before, invocationCount(alice.principalId), "被拒的请求不该占用幂等键");
    }

    @Test
    void theSameKeyReplaysTheSameBody() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        long before = invocationCount(alice.principalId);
        String key = "move-" + UUID.randomUUID();

        ActionGateway.ActionExecution first = gateway.execute(move(board, 4), alice.principal(), key);
        ActionGateway.ActionExecution second = gateway.execute(move(board, 4), alice.principal(), key);

        assertEquals(ActionStatus.SUCCESS, first.response().status());
        assertFalse(first.replayed());
        assertTrue(second.replayed(), "第二次必须被识别为重放");
        assertEquals(serialize(first.response()), serialize(second.response()));
        assertEquals(before + 1, invocationCount(alice.principalId),
                "两次请求只该落下<em>一行</em>调用记录");
    }

    @Test
    void theSameKeyWithADifferentBodyIsRefused() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        String key = "move-" + UUID.randomUUID();

        gateway.execute(move(board, 4), alice.principal(), key);
        ActionGateway.ActionExecution reused = gateway.execute(move(board, 8), alice.principal(), key);

        assertEquals(ActionStatus.IDEMPOTENCY_KEY_REUSED, reused.response().status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.response().error().code());
        assertNull(boardRow(board.uri).state().path("board").get(8).textValue(),
                "被拒的载荷不该落盘");
    }

    // ─────────────────────────── 权限 ───────────────────────────

    /**
     * 有身份、但不在这一局里 → 拒绝, 而且给出的是一个稳定的码。
     *
     * <p>v1 这里断言的是"没装这个应用", v2 是 {@code NOT_A_PARTICIPANT} —— 同一个位置,
     * 换了一个问题: 从"你装过这个应用吗"变成"<b>你在这场里吗</b>"。码本身必须稳定, 因为客户端要
     * 靠它决定下一步: 这个码的下一步是去求人邀请自己, 而不是去修自己的请求体。
     */
    @Test
    void aPrincipalOutsideTheSessionIsDenied() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        Human stranger = stranger();

        ActionGateway.ActionExecution execution = gateway.execute(
                move(board, 4), stranger.principal(), "key-" + UUID.randomUUID());

        assertEquals(ActionStatus.DENIED, execution.response().status());
        assertEquals("NOT_A_PARTICIPANT", execution.response().error().code());
    }

    /** 不在场的人向 {@code pendingActions} 提问, 得到空列表而不是异常 —— "我不想做任何事"。 */
    @Test
    void pendingActionsIsEmptyForAPrincipalOutsideTheSession() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        Human stranger = stranger();

        assertTrue(gateway.pendingActions(board.uri, ctx(stranger)).isEmpty());
    }

    // ─────────────────────────── 参数与解析 ───────────────────────────

    @Test
    void anUnknownActionIsNotFound() {
        Human alice = newHuman();
        ActionResponse response = gateway.execute(
                ActionRequest.of("game.teleport", "game://session/" + UUID.randomUUID(), null), ctx(alice));

        assertEquals(ActionStatus.NOT_FOUND, response.status());
        assertEquals("ACTION_NOT_FOUND", response.error().code());
    }

    @Test
    void aMissingTargetIsRejected() {
        Human alice = newHuman();
        ActionResponse response = gateway.execute(ActionRequest.of(MAKE_MOVE, null, null), ctx(alice));

        assertEquals(ActionStatus.INVALID_ARGUMENT, response.status());
        assertEquals("TARGET_REQUIRED", response.error().code());
    }

    @Test
    void aMissingActionIsRejected() {
        Human alice = newHuman();
        assertEquals(ActionStatus.INVALID_ARGUMENT,
                gateway.execute(new ActionRequest("  ", "game://session/x", null, null), ctx(alice)).status());
    }

    // ─────────────────────────── 并发写 ───────────────────────────

    /** 拿着一个过期的 {@code expectedResourceVersion} 落子 → 干净的 409, 并带回当前版本。 */
    @Test
    void aStaleExpectedResourceVersionIsAConflict() {
        Human alice = newHuman();
        Board board = newBoard(alice);
        String key = "move-" + UUID.randomUUID();

        ActionResponse response = gateway.execute(
                new ActionRequest(MAKE_MOVE, board.uri, input(4), 99L), alice.principal(), key).response();

        assertEquals(ActionStatus.STATE_CONFLICT, response.status());
        assertEquals("STATE_CONFLICT", response.error().code());
        assertNotNull(response.resource(), "冲突要把当前资源带回去, 调用方才能立刻重试");
        assertEquals(1L, response.resource().version());
        assertNull(boardRow(board.uri).state().path("board").get(4).textValue(), "冲突的落子不落盘");
    }

    // ─────────────────────────── 多参与方 ───────────────────────────

    /**
     * 真人先落一子, 数字人问"我能做什么" —— 应用回答"落子", 而真人再问时得到空。
     * 平台在这里没有任何关于棋的逻辑, 它只是把问题转给应用。
     */
    @Test
    void pendingActionsComeFromTheApplicationNotFromThePlatform() {
        Human alice = newHuman();
        Agent bob = newAgent();
        Board board = newBoard(alice, bob);

        // 开局先手是 X, 也就是 0 号座位的真人
        assertEquals(List.of(MAKE_MOVE, "game.surrender"), pendingIds(board, ctx(alice)));
        assertTrue(gateway.pendingActions(board.uri, ctx(bob)).isEmpty(),
                "轮到别人时不给动作 —— 否则 Agent 会自问自答连下两步");

        assertEquals(ActionStatus.SUCCESS,
                gateway.execute(move(board, 0), alice.principal(), "k-" + UUID.randomUUID()).response().status());

        assertEquals(List.of(MAKE_MOVE, "game.surrender"), pendingIds(board, ctx(bob)), "轮到数字人了");
        assertTrue(gateway.pendingActions(board.uri, ctx(alice)).isEmpty(), "真人走完了, 该等对方");
    }

    /** 终局之后谁也不该被叫醒 —— 应用说"没人在等下一手", 平台照转。 */
    @Test
    void pendingActionsGoQuietOnceTheGameIsOver() {
        Human alice = newHuman();
        Agent bob = newAgent();
        Board board = newBoard(alice, bob);

        gateway.execute(move(board, 0), alice.principal(), "a-" + UUID.randomUUID());   // X
        gateway.execute(move(board, 3), bob.principal(), "b-" + UUID.randomUUID());     // O
        gateway.execute(move(board, 1), alice.principal(), "c-" + UUID.randomUUID());   // X
        gateway.execute(move(board, 4), bob.principal(), "d-" + UUID.randomUUID());     // O
        gateway.execute(move(board, 2), alice.principal(), "e-" + UUID.randomUUID());   // X 三连

        assertEquals("X", boardRow(board.uri).state().path("winner").asText());
        assertTrue(gateway.pendingActions(board.uri, ctx(alice)).isEmpty());
        assertTrue(gateway.pendingActions(board.uri, ctx(bob)).isEmpty());
    }

    /**
     * 在场、也有写权限, 但<em>不是对局者</em>: 能读, 落子被<b>应用</b>拒绝。
     *
     * <p>这一条测的是两层的分界。v2 的平台权限只到"你在不在这一局里", 再往下的"你是不是这盘棋的
     * 棋手"是应用自己的规则 —— 所以这里的 carol 必须<em>先被邀请进会话</em>(否则她连读都读不到,
     * 那就变成测平台权限了), 然后应用才用 {@code NOT_A_PLAYER} 拒掉她的落子。
     */
    @Test
    void anInvitedBystanderCanReadButTheApplicationRefusesTheirMove() {
        Human alice = newHuman();
        Agent bob = newAgent();
        Board board = newBoard(alice, bob);
        Human carol = newHuman();
        invite(board, carol);

        assertEquals(ActionStatus.SUCCESS,
                gateway.execute(ActionRequest.of(STATE, board.uri, null), ctx(carol)).status());

        ActionResponse response = gateway.execute(
                new ActionRequest(MAKE_MOVE, board.uri, input(4), null), carol.principal(), "k-" + UUID.randomUUID()).response();
        assertEquals(ActionStatus.FAILED, response.status());
        assertEquals("NOT_A_PLAYER", response.error().code());
    }

    @Test
    void playingOutOfTurnIsRefusedByTheApplication() {
        Human alice = newHuman();
        Agent bob = newAgent();
        Board board = newBoard(alice, bob);

        gateway.execute(move(board, 0), alice.principal(), "k1-" + UUID.randomUUID());
        ActionResponse twice = gateway.execute(move(board, 1), alice.principal(), "k2-" + UUID.randomUUID()).response();

        assertEquals(ActionStatus.FAILED, twice.status());
        assertEquals("NOT_YOUR_TURN", twice.error().code());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private record Human(String principalId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
        }
    }

    private record Agent(String companionId, String userId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId, userId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_INTERNAL);
        }
    }

    private record Board(String uri, ApplicationSessionRecord session, ActionResponse create) {
    }

    private Human newHuman() {
        String principalId = UUID.randomUUID().toString();   // 真人 principal id 就是 user id
        return new Human(principalId);
    }

    /**
     * 有身份、但<em>不在这一局里</em>。{@link #newBoard} 会把开局的人记成参与者, 所以拿
     * {@code newHuman()} 当陌生人用会让 {@code NOT_A_PARTICIPANT} 那条断言测到别的东西上去
     * —— 这个夹具的存在就是为了不犯那个错。
     */
    private Human stranger() {
        return new Human(UUID.randomUUID().toString());
    }

    private Agent newAgent() {
        return new Agent(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    /** 开一局新的, 把 {@code alice} 记为 OWNER; 有对手就顺手把他邀请进来。 */
    private Board newBoard(Human alice, Agent opponent) {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, alice.principal());
        if (opponent != null) {
            // viaInvitation: 会话默认 INVITE_ONLY, 而"被邀请进来"正是对手的处境。
            // 这一句也是 v2 与 v1 在测试夹具上最大的区别 —— v1 只要给对手一行 installation,
            // v2 要让对手真的在这个会话里有一行参与者。
            participantService.join(session.getId(), opponent.principal(),
                    SessionParticipantRecord.ROLE_MEMBER, true);
        }
        String uri = "game://session/" + session.getId();
        JsonNode input = opponent == null
                ? null : objectMapper.createObjectNode().put("opponentPrincipalId", opponent.companionId());
        ActionResponse create = gateway.execute(ActionRequest.of(CREATE, uri, input), ctx(alice));
        return new Board(uri, session, create);
    }

    private Board newBoard(Human alice) {
        return newBoard(alice, null);
    }

    private Board newBoard() {
        return newBoard(newHuman());
    }

    /** 把一个人拉进这一局 —— 只有被邀请的人才进得来, 所以这里是 {@code viaInvitation=true}。 */
    private void invite(Board board, Human who) {
        participantService.join(board.session().getId(), who.principal(),
                SessionParticipantRecord.ROLE_MEMBER, true);
    }

    private static InvocationContext ctx(Human human) {
        return human.principal().toInvocationContext();
    }

    private static InvocationContext ctx(Agent agent) {
        return agent.principal().toInvocationContext();
    }

    private ActionRequest move(Board board, int position) {
        return new ActionRequest(MAKE_MOVE, board.uri, input(position), null);
    }

    private JsonNode input(int position) {
        return objectMapper.createObjectNode().put("position", position);
    }

    private ResourceView boardRow(String uri) {
        return gateway.read(uri).orElseThrow(() -> new AssertionError("资源应当存在: " + uri));
    }

    private long invocationCount(String principalId) {
        return invocations.findAll().stream()
                .filter(r -> principalId.equals(r.getPrincipalId()))
                .count();
    }

    private List<String> pendingIds(Board board, InvocationContext ctx) {
        return gateway.pendingActions(board.uri, ctx).stream().map(ActionSpec::actionId).toList();
    }

    private String serialize(ActionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
