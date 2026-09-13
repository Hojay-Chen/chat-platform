package com.luxera.companion.application.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.action.ResourceAccess;
import com.luxera.companion.application.repository.ResourceRepository;
import com.luxera.companion.contracts.application.ResourceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resource 是统一读模型, 而它是<em>共享</em>的 —— 真人和 Agent 读同一行, 也写同一行。
 * 于是这个类里最重要的那条测试是并发的: 两个线程拿着同一个 {@code state_version} 写,
 * 必须恰好一个成功。
 *
 * <p>为什么这条不能用单线程的"用旧版本号写 → 失败"代替: 单线程版本证明的是"SQL 里有
 * {@code and state_version = ?}", 并发版本证明的是"这条 WHERE 真的挡住了两个人"。
 * 区别在于前者在把 CAS 换成
 * {@code select` 然后 `if (version != expected) throw} 之后<em>仍然通过</em> —— 而那正是
 * 一个 TOCTOU 漏洞。所以两个都写, 并发那个造假不了。
 *
 * <p>写必须发生在事务里({@code compareAndSet} 是 {@code @Modifying}), 测试里用
 * {@link TransactionTemplate} 显式框出来 —— 这也顺带把"两个线程各自有独立事务"这件事
 * 变成了测试的前提, 而不是一个隐式的巧合。
 */
@ActiveProfiles("test")
@SpringBootTest
class ResourceStoreTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String TYPE = "tictactoe.game";

    @Autowired
    ResourceStore store;

    @Autowired
    ResourceRepository resources;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    // ─────────────────────────── 创建 ───────────────────────────

    @Test
    void firstWriteCreatesTheResourceAtVersionOne() {
        String uri = uri();
        ResourceView view = inTransaction(() ->
                scoped().write(uri, TYPE, state("first"), null));

        assertEquals(1L, view.version());
        assertEquals(uri, view.uri());
        assertEquals(TYPE, view.resourceType());
        assertEquals(APP_ID, view.applicationId());
        assertEquals(SESSION, view.sessionId());
        assertEquals("first", view.state().path("mark").asText());
        assertTrue(store.existsInStore(uri));
    }

    /** {@code resource_type} 留空时退回应用 id —— 宁可类型粗一点, 也不要一行没有类型的资源。 */
    @Test
    void blankResourceTypeFallsBackToTheApplicationId() {
        String uri = uri();
        ResourceView view = inTransaction(() -> scoped().write(uri, "  ", state("x"), null));

        assertEquals(APP_ID, view.resourceType());
    }

    @Test
    void writeIncrementsTheVersionEachTime() {
        String uri = uri();
        inTransaction(() -> scoped().write(uri, TYPE, state("a"), null));
        inTransaction(() -> scoped().write(uri, TYPE, state("b"), null));
        ResourceView third = inTransaction(() -> scoped().write(uri, TYPE, state("c"), null));

        assertEquals(3L, third.version());
        assertEquals("c", store.find(uri).orElseThrow().state().path("mark").asText());
    }

    /** 不传 expectedVersion 时先读一次再 CAS —— 顺序写不会白白冲突。 */
    @Test
    void omittedExpectedVersionReadsThenCasInTheSameTransaction() {
        String uri = uri();
        inTransaction(() -> scoped().write(uri, TYPE, state("a"), null));
        ResourceView second = inTransaction(() -> scoped().write(uri, TYPE, state("b"), null));

        assertEquals(2L, second.version());
    }

    // ─────────────────────────── 并发 CAS ───────────────────────────

    /**
     * <b>核心用例。</b>两个线程各持一个早已读到的版本 1 同时写: 恰好一个成功, 另一个拿到
     * {@code StateConflictException}, 且异常里带着<em>当前</em>版本与<em>当前</em>状态 ——
     * 调用方据此可以立刻重读重试, 而不是拿到一个失败就放弃。
     */
    @Test
    void twoWritersWithTheSameVersionProduceExactlyOneWinner() throws Exception {
        String uri = uri();
        inTransaction(() -> scoped().write(uri, TYPE, state("init"), null));
        long readVersion = 1L;

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger wins = new AtomicInteger();
        AtomicReference<StateConflictException> conflict = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try {
            for (String mark : List.of("A", "B")) {
                pool.submit(() -> {
                    try {
                        start.await();
                        inTransaction(() -> scoped().write(uri, TYPE, state(mark), readVersion));
                        wins.incrementAndGet();
                    } catch (StateConflictException e) {
                        conflict.compareAndSet(null, e);
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "两个写线程应当都在 30 秒内结束");
        } finally {
            pool.shutdownNow();
        }

        assertNull(unexpected.get(), "不该出现 CAS 之外的异常: " + unexpected.get());
        assertEquals(1, wins.get(), "恰好一个写成功");
        assertNotNull(conflict.get(), "输的那个必须拿到 STATE_CONFLICT, 而不是静默失败");

        StateConflictException e = conflict.get();
        assertEquals(uri, e.uri());
        assertEquals(1L, e.expectedVersion());
        assertEquals(2L, e.currentVersion(), "异常要带回当前版本, 调用方才能立刻重试");
        assertNotNull(e.currentStateJson(), "异常要带回当前状态");

        ResourceView current = store.find(uri).orElseThrow();
        assertEquals(2L, current.version(), "只加了一次版本 —— 输的那次没有落盘");
        assertTrue(e.currentStateJson().contains("A") || e.currentStateJson().contains("B"),
                "带回的状态应当是赢家的那次写入: " + e.currentStateJson());
    }

    /** 陈旧版本号单线程也要被拒 —— 这是 CAS 的语义本身, 与并发无关。 */
    @Test
    void staleExpectedVersionIsRefused() {
        String uri = uri();
        inTransaction(() -> scoped().write(uri, TYPE, state("a"), null));
        inTransaction(() -> scoped().write(uri, TYPE, state("b"), null));   // 版本到 2

        StateConflictException e = assertThrows(StateConflictException.class,
                () -> inTransaction(() -> scoped().write(uri, TYPE, state("c"), 1L)));

        assertEquals(1L, e.expectedVersion());
        assertEquals(2L, e.currentVersion());
        assertEquals("b", store.find(uri).orElseThrow().state().path("mark").asText(), "冲突的写不落地");
    }

    /** 未来版本号同样是冲突 —— "版本 99 还不存在"不是可以顺手创建的东西。 */
    @Test
    void futureExpectedVersionIsRefused() {
        String uri = uri();
        inTransaction(() -> scoped().write(uri, TYPE, state("a"), null));

        assertThrows(StateConflictException.class,
                () -> inTransaction(() -> scoped().write(uri, TYPE, state("b"), 99L)));
    }

    // ─────────────────────────── 读 ───────────────────────────

    @Test
    void readingAnUnknownUriIsEmptyRatherThanAnError() {
        assertTrue(store.find("game://session/" + UUID.randomUUID()).isEmpty());
        assertTrue(store.find((String) null).isEmpty());
        assertTrue(store.find("  ").isEmpty());
        assertNull(scoped().read("game://session/" + UUID.randomUUID()), "还没有是正常状态, 不是异常");
        assertFalse(store.existsInStore("game://session/" + UUID.randomUUID()));
        assertFalse(store.existsInStore(null));
    }

    /** 集合型资源没有会话 —— {@code session_id} 为空是合法的, 不该被当成脏数据。 */
    @Test
    void resourcesWithoutASessionAreAllowed() {
        String uri = "reminder://pending";
        ResourceView view = inTransaction(() ->
                store.scoped(APP_ID, null).write(uri, "reminder.item", state("p"), null));

        assertNull(view.sessionId());
        assertEquals("p", store.find(uri).orElseThrow().state().path("mark").asText());
        // 没有会话就查不到"这个会话下的资源", 但它仍然属于这个应用
        assertTrue(store.bySession(null).isEmpty());
        assertTrue(store.byApplication(APP_ID).stream().anyMatch(v -> v.uri().equals(uri)));
    }

    /**
     * <b>资源行的会话锚跟着平台解出来的会话走。</b>
     *
     * <p>这是 {@code ResourceStore.reanchorIfStale} 的那条路径, 也是它唯一说得清的存在理由:
     * URI 里<em>没有</em> {@code sessionId} 段时, 资源的会话锚只能靠写入那一刻解出来的会话定下来。
     * 那个会话后来被回收器收掉、平台给他解出新的一个时, 锚必须跟着走 —— 否则资源照常能读写
     * ({@code ActionGateway} 的第 2 档发现锚不可用会重新解一次), 但
     * {@code AgentRouteResolver} 会拿着一个空会话去查参与者, <b>一个人也唤不醒, 而且不报错</b>。
     *
     * <p>注意这条用例造的是一个"没有会话段、但确实落库"的资源 —— 它不是提醒收件箱: 提醒是
     * {@code APP_OWNED}, {@code resource} 表里根本没有它那一行(见
     * {@link #appOwnedResourcesComeFromTheProjectorNotTheTable})。这个区别要紧, 因为把两者混为
     * 一谈会让人以为提醒的锚也该跟着走, 然后去修一个不存在的东西。
     */
    @Test
    void aResourceWithoutASessionSegmentFollowsTheSessionResolvedLater() {
        String uri = "collection://" + UUID.randomUUID();
        String firstSession = UUID.randomUUID().toString();
        String nextSession = UUID.randomUUID().toString();

        ResourceView created = inTransaction(() ->
                store.scoped(APP_ID, firstSession).write(uri, "collection.items", state("a"), null));
        assertEquals(firstSession, created.sessionId());

        ResourceView moved = inTransaction(() ->
                store.scoped(APP_ID, nextSession).write(uri, "collection.items", state("b"), null));

        assertEquals(nextSession, moved.sessionId(), "锚跟着这次解出来的会话走");
        assertEquals(nextSession, store.find(uri).orElseThrow().sessionId(), "而且是真写进了库里");
        assertEquals(2L, moved.version(), "重挂锚不该顺手把版本推进一次以上");
        assertTrue(store.bySession(nextSession).stream().anyMatch(v -> v.uri().equals(uri)));
        assertTrue(store.bySession(firstSession).isEmpty(), "旧锚上不该还留着它");
    }

    @Test
    void bySessionAndByApplicationListWhatWasWritten() {
        String uri = uri();
        inTransaction(() -> scoped().write(uri, TYPE, state("a"), null));

        assertTrue(store.bySession(SESSION).stream().anyMatch(v -> v.uri().equals(uri)));
        assertTrue(store.byApplication(APP_ID).stream().anyMatch(v -> v.uri().equals(uri)));
        assertTrue(store.bySession("  ").isEmpty());
    }

    // ─────────────────────────── 投影 (APP_OWNED) ───────────────────────────

    /**
     * {@code APP_OWNED} 资源在 {@code resource} 表里<em>没有行</em>, 读的时候由投影器给出。
     * 这里用一个手工构造的 {@code ResourceStore} 而非容器里的那个: 内置应用还没有投影器,
     * 而这条路径只有等提醒应用(R5)进来才会被真正用到 —— 现在就用测试把它钉住, 好过那时候
     * 一边写投影器一边猜这张接口长什么样。
     */
    @Test
    void appOwnedResourcesComeFromTheProjectorNotTheTable() {
        String uri = "reminder://item/" + UUID.randomUUID();
        ResourceView projected = new ResourceView(uri, "reminder.item", APP_ID, null,
                state("projected"), 7L, null, null);
        ResourceProjector projector = new ResourceProjector() {
            @Override
            public boolean supports(String candidate) {
                return candidate.startsWith("reminder://");
            }

            @Override
            public java.util.Optional<ResourceView> project(String candidate) {
                return uri.equals(candidate) ? java.util.Optional.of(projected) : java.util.Optional.empty();
            }
        };
        ResourceStore withProjector = new ResourceStore(resources, objectMapper, List.of(projector));

        assertEquals("projected", withProjector.find(uri).orElseThrow().state().path("mark").asText());
        assertEquals(7L, withProjector.find(uri).orElseThrow().version());
        // 投影不等于落库: 表里没有行, 所以"这条资源存在吗"的答案是"不在 resource 表里"
        assertFalse(withProjector.existsInStore(uri));
        // 非 APP_OWNED 的 URI 仍然走表
        assertTrue(withProjector.find("game://session/" + UUID.randomUUID()).isEmpty());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 会话 id 就是 ApplicationSession 的主键, 长度 36 的 UUID —— 加前缀会超列宽。 */
    private static final String SESSION = UUID.randomUUID().toString();

    private ResourceAccess scoped() {
        return store.scoped(APP_ID, SESSION);
    }

    private String uri() {
        return "game://session/" + UUID.randomUUID();
    }

    private JsonNode state(String mark) {
        return objectMapper.createObjectNode().put("mark", mark);
    }

    /** CAS 必须在事务里跑; 测试里显式框出来, 而不是靠 {@code @Transactional} 把整个用例包住。 */
    private <T> T inTransaction(java.util.function.Supplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> body.get());
    }
}
