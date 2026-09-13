package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.domain.ActionInvocationRecord;
import com.luxera.companion.application.domain.InvocationStatus;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ActionInvocationRepository;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等 —— <b>真的实现</b>, 不是"收下 key 就丢掉"。
 *
 * <p>这个类守着三件事, 每一件都对应一种线上事故:
 *
 * <ol>
 *   <li><b>并发同 key 只执行一次。</b>靠的是让唯一索引当锁, 而不是 check → insert → execute。
 *       后者是 TOCTOU, 两个并发请求会双双通过检查, 然后同一步棋落两次。</li>
 *   <li><b>已终态按原响应重放。</b>{@code Idempotent-Replay} 的语义基础: 客户端网络抖动重发,
 *       拿到的必须是同一次结果, 而不是"又执行了一次"。</li>
 *   <li><b>崩溃恢复有定义。</b>JVM 死在执行中途会留下一条永远停在 {@code IN_PROGRESS} 的行;
 *       没有恢复机制的话那个 key 就<em>永久</em>坏掉了 —— 重试永远 409。这里把三种情形都钉住:
 *       未超时 409、已超时可抢占重放、抢占失败回到 409。</li>
 * </ol>
 *
 * <p>时间阈值直接从 {@link IdempotencyService} 读({@code invocationTimeout}/{@code reapAfter}),
 * 而不是在测试里写死 60s / 900s: 阈值是配置项, 测试该跟着配置走, 否则改了配置测试就开始
 * 用错误的假设测别的东西。
 */
@ActiveProfiles("test")
@SpringBootTest
class IdempotencyStoreTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String MAKE_MOVE = "game.make_move";

    /**
     * 本类里所有调用的会话。用一个常量而不是每条用例各编一个: 这些用例要测的是"同一个 key 在
     * <em>同一个会话</em>里的行为", 会话说变就变的话, 它们会各自退化成"两个不相干的 key"。
     * 需要验证"换个会话就是另一回事"的那一条单独写, 见
     * {@link #theSameKeyUnderAnotherSessionIsIndependent}。
     */
    private static final String SESSION = "session-under-test";

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    ActionInvocationRepository invocations;

    @Autowired
    ManifestRegistry manifests;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    // ─────────────────────────── 并发 ───────────────────────────

    /**
     * 四个线程拿同一个 key 同时进来, <b>只能有一个拿到执行权</b>, 且库里只有一行。
     * 其余三个必须拿到明确的"执行中"答复, 而不是也去执行一遍。
     */
    @Test
    void concurrentClaimsWithTheSameKeyExecuteOnce() throws Exception {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();
        String hash = hash(4);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<IdempotencyService.Claim> claims = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        claims.add(idempotency.claim(key, human(principalId), resolution(), SESSION, hash));
                    } catch (Exception ignored) {
                        // 认领本身抛异常也是"没抢到"的一种, 不算成功执行
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "四个线程都该在 30 秒内结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(4, claims.size(), "四个请求都该得到答复(执行 / 重放 / 拒绝)");
        assertEquals(1, claims.stream().filter(IdempotencyService.Claim::shouldExecute).count(),
                "恰好一个执行");
        assertEquals(3, claims.stream().filter(c -> c.conflict() != null).count(),
                "其余三个必须拿到执行中的答复");

        List<ActionInvocationRecord> rows = rowsWith(principalId, key);
        assertEquals(1, rows.size(), "唯一键必须把并发的第二个插入挡在门外");
        assertEquals(InvocationStatus.IN_PROGRESS, rows.get(0).statusEnum());
    }

    // ─────────────────────────── 重放 ───────────────────────────

    @Test
    void terminalInvocationReplaysTheSameResponse() {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();
        String hash = hash(0);

        IdempotencyService.Claim first = idempotency.claim(key, human(principalId), resolution(), SESSION, hash);
        assertTrue(first.shouldExecute());

        ActionResponse original = ActionResponse.success(
                objectMapper.createObjectNode().put("move", "accepted"), null);
        completeInTransaction(first.record().getId(), original);

        IdempotencyService.Claim second = idempotency.claim(key, human(principalId), resolution(), SESSION, hash);

        assertFalse(second.shouldExecute(), "已终态的 key 不该再执行一次");
        assertNull(second.conflict());
        assertNotNull(second.replay());
        assertEquals(ActionStatus.SUCCESS, second.replay().status());
        assertEquals("accepted", second.replay().result().path("move").asText());
        // 重放的必须是同一次响应本身 —— 客户端据此判断"这就是刚才那次"
        assertEquals(idempotency.serialize(original), idempotency.serialize(second.replay()));
    }

    @Test
    void sameKeyWithADifferentPayloadIsRejectedRatherThanSilentlyReplayed() {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();

        idempotency.claim(key, human(principalId), resolution(), SESSION, hash(0));

        IdempotencyService.Claim reused = idempotency.claim(key, human(principalId), resolution(), SESSION, hash(8));

        assertNull(reused.replay(), "载荷不同绝不能重放");
        assertNotNull(reused.conflict());
        assertEquals(ActionStatus.IDEMPOTENCY_KEY_REUSED, reused.conflict().status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.conflict().error().code());
    }

    /** 同一个 key 换个 principal 是另一码事 —— 真人的 key 不该和 Agent 的撞车。 */
    @Test
    void theSameKeyUnderAnotherPrincipalIsIndependent() {
        String key = "key-" + UUID.randomUUID();
        String alice = principalId();
        String bob = principalId();

        assertTrue(idempotency.claim(key, human(alice), resolution(), SESSION, hash(0)).shouldExecute());
        assertTrue(idempotency.claim(key, human(bob), resolution(), SESSION, hash(0)).shouldExecute(),
                "作用域里带 principal, 所以同一个 key 对另一个人是新的");
    }

    /**
     * LAP v2 (方案 §49): 同一个人、同一个 key, <b>换一个会话就是另一回事</b>。
     *
     * <p>作用域里加 {@code session_id} 换来的是这个。少了它, 一个客户端在两局棋里用同一个
     * 自增序号当 key, 第二局的第一步会被当成第一局那一步的重放 —— 拿到的是一盘<em>别人的</em>
     * 棋的响应, 而且不报错。
     *
     * <p>这一条同时守着那条更隐蔽的性质: 唯一键里带一个<em>可空</em>列就等于没带它
     * (PostgreSQL 的唯一索引对 NULL 互不相等)。所以这里造的必须是两个真实、非空的会话 id ——
     * 如果哪天有人把它改成 {@code null}, 这条用例会变成"两次独立执行"而<em>通过</em>,
     * 于是它保护不了任何东西。真正把这条钉死在库层面的是实体上的 {@code nullable = false}。
     */
    @Test
    void theSameKeyUnderAnotherSessionIsIndependent() {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();
        // 不戴前缀: session_id 是 varchar(36), 因为真实的会话 id 就是一个 UUID。
        // 写成 "session-" + UUID 会变成 44 个字符 —— 那不是"另一局", 那是一行插不进去。
        String thisGame = UUID.randomUUID().toString();
        String nextGame = UUID.randomUUID().toString();

        assertTrue(idempotency.claim(key, human(principalId), resolution(), thisGame, hash(0)).shouldExecute());
        assertTrue(idempotency.claim(key, human(principalId), resolution(), nextGame, hash(0)).shouldExecute(),
                "作用域里带 session, 所以同一个 key 在另一局里是新的");
        assertEquals(2, rowsWith(principalId, key).size(), "两局各留下自己的一行");
    }

    // ─────────────────────────── 崩溃恢复 ① ───────────────────────────

    @Test
    void inProgressWithinTheTimeoutIsRefusedNotStolen() {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();
        String hash = hash(0);

        assertTrue(idempotency.claim(key, human(principalId), resolution(), SESSION, hash).shouldExecute());

        IdempotencyService.Claim retry = idempotency.claim(key, human(principalId), resolution(), SESSION, hash);

        assertFalse(retry.shouldExecute(), "还在超时窗口内, 不能抢占");
        assertEquals(ActionStatus.IDEMPOTENCY_IN_PROGRESS, retry.conflict().status());
        assertEquals(1, row(principalId, key).getAttemptCount(), "没被抢占就不该涨尝试次数");
    }

    // ─────────────────────────── 崩溃恢复 ② ───────────────────────────

    @Test
    void staleInProgressIsStolenAndReExecuted() {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();
        String hash = hash(0);

        IdempotencyService.Claim first = idempotency.claim(key, human(principalId), resolution(), SESSION, hash);
        assertTrue(first.shouldExecute());
        backdate(principalId, key, idempotency.invocationTimeout().plusMinutes(4));

        IdempotencyService.Claim retry = idempotency.claim(key, human(principalId), resolution(), SESSION, hash);

        assertTrue(retry.shouldExecute(), "超时后应当允许抢占并重新执行");
        assertEquals(first.record().getId(), retry.record().getId(), "抢占的是同一行, 不是新建一行");
        assertEquals(2, row(principalId, key).getAttemptCount(), "抢占即记一次尝试");
        assertTrue(row(principalId, key).getStartedAt().isAfter(LocalDateTime.now().minusMinutes(1)),
                "抢占要把 started_at 推到当下, 否则下一个请求又会立刻抢占");
    }

    // ─────────────────────────── 崩溃恢复 ③ ───────────────────────────

    /**
     * 两个重试者同时看到同一个超时行 —— CAS 保证只有一个抢到。抢不到的那个回到"执行中",
     * 而不是也去执行一遍。这正是不写成"先读后改"的原因。
     */
    @Test
    void concurrentStealsOnAStaleRowProduceExactlyOneWinner() throws Exception {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();
        String hash = hash(0);

        idempotency.claim(key, human(principalId), resolution(), SESSION, hash);
        backdate(principalId, key, idempotency.invocationTimeout().plusMinutes(4));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<IdempotencyService.Claim> claims = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        claims.add(idempotency.claim(key, human(principalId), resolution(), SESSION, hash));
                    } catch (Exception ignored) {
                        // 抢不到也是一种结果
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "两个重试者都该在 30 秒内结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, claims.size());
        assertEquals(1, claims.stream().filter(IdempotencyService.Claim::shouldExecute).count(),
                "恰好一个抢到");
        assertEquals(1, claims.stream().filter(c -> c.conflict() != null).count(),
                "抢不到的那个回到执行中");
        assertEquals(2, row(principalId, key).getAttemptCount(), "只涨了一次尝试");
    }

    // ─────────────────────────── 回收器 ───────────────────────────

    /** 崩溃遗留封成 {@code EXPIRED}, <b>绝不静默删除</b> —— 删了就没法解释"那次到底跑没跑"。 */
    @Test
    void reaperExpiresAbandonedInvocationsInsteadOfDeletingThem() {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();
        idempotency.claim(key, human(principalId), resolution(), SESSION, hash(0));
        backdate(principalId, key, idempotency.reapAfter().plusMinutes(5));

        int reaped = idempotency.reapStale();

        assertTrue(reaped >= 1, "至少回收了这一条");
        ActionInvocationRecord sealed = row(principalId, key);
        assertEquals(InvocationStatus.EXPIRED, sealed.statusEnum());
        assertEquals("INVOCATION_EXPIRED", sealed.getErrorCode());
        assertNotNull(sealed.getCompletedAt());
        assertNotNull(sealed.getResponseJson(), "要留下可重放的答复, 不能只有一个状态字段");

        // 封过之后再重试: 拿到的是 EXPIRED 的答复, 而不是又执行一次
        IdempotencyService.Claim retry = idempotency.claim(key, human(principalId), resolution(), SESSION, hash(0));
        assertFalse(retry.shouldExecute(), "EXPIRED 是终态, 不该复活");
        assertEquals(ActionStatus.EXPIRED, retry.replay().status());
    }

    @Test
    void reaperLeavesFreshInProgressAlone() {
        String principalId = principalId();
        String key = "key-" + UUID.randomUUID();
        idempotency.claim(key, human(principalId), resolution(), SESSION, hash(0));

        idempotency.reapStale();

        assertEquals(InvocationStatus.IN_PROGRESS, row(principalId, key).statusEnum(),
                "还在跑的执行不该被回收器烧掉");
    }

    // ─────────────────────────── 载荷指纹 ───────────────────────────

    /** {@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}} 是同一个请求 —— 否则"同 key 重发"会被误判成 422。 */
    @Test
    void requestHashIgnoresFieldOrderButNotValues() {
        String ab = hashOf("{\"a\":1,\"b\":2}");
        String ba = hashOf("{\"b\":2,\"a\":1}");
        String changed = hashOf("{\"a\":1,\"b\":3}");

        assertEquals(ab, ba);
        assertFalse(ab.equals(changed));
        assertEquals(hashOf("{\"a\":1}"), hashOf("{\"a\":1}"), "同一个载荷必须稳定");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private void completeInTransaction(String invocationId, ActionResponse response) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> idempotency.completeInCurrentTransaction(invocationId, response));
    }

    private void backdate(String principalId, String key, Duration age) {
        ActionInvocationRecord row = row(principalId, key);
        row.setStartedAt(LocalDateTime.now().minus(age));
        invocations.saveAndFlush(row);
    }

    /**
     * 这个 principal 在这个 key 上的那一行。
     *
     * <p>刻意用"筛全部"而不是仓储上那个按键查找的派生方法: 那个方法要求"只有一行", 而本类里
     * 恰好有一条用例就是要造出同一个 key 的两行(两个会话各一行)。写得窄一点, 那条用例才测得了。
     */
    private ActionInvocationRecord row(String principalId, String key) {
        List<ActionInvocationRecord> rows = rowsWith(principalId, key);
        assertEquals(1, rows.size(), "这个 key 在这个 principal 下应当只有一行: " + key);
        return rows.get(0);
    }

    private List<ActionInvocationRecord> rowsWith(String principalId, String key) {
        return invocations.findAll().stream()
                .filter(r -> principalId.equals(r.getPrincipalId()) && key.equals(r.getIdempotencyKey()))
                .toList();
    }

    private ActionResolution resolution() {
        ApplicationManifest manifest = manifests.published(APP_ID)
                .orElseThrow(() -> new AssertionError("内置应用应当已注册"));
        return new ActionResolution(manifest, manifest.action(MAKE_MOVE).orElseThrow());
    }

    private String hash(int position) {
        return hashOf("{\"position\":" + position + "}");
    }

    private String hashOf(String inputJson) {
        try {
            ActionRequest request = ActionRequest.of(MAKE_MOVE, "game://session/x",
                    objectMapper.readTree(inputJson));
            return IdempotencyService.requestHash(objectMapper, request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String principalId() {
        return "idem-" + UUID.randomUUID();
    }

    private static ResolvedPrincipal human(String principalId) {
        return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }
}
