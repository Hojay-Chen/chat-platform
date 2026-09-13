package com.luxera.companion.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.domain.OutboxEventRecord;
import com.luxera.companion.application.domain.SubscriptionRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestCatalogueSync;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.LapOutboxRepository;
import com.luxera.companion.application.repository.SubscriptionRepository;
import com.luxera.companion.application.session.SubscriptionService;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.SubscriptionRequest;
import com.luxera.companion.contracts.spi.ApplicationEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v1 §Event: <b>收件箱让 {@code INBOX} 从"记录"变成"真的会送达"。</b>
 *
 * <p>这里断言的是那件直投路径做不到的事: 事件先落成一行数据, 之后才谈投递。于是进程在任何
 * 位置死掉都不会丢 —— 重启之后 PENDING 还在, 下一轮接着投。失败也不回滚、不丢弃, 而是留在
 * 原地重试, 试满才判死。
 *
 * <p>relay 在本类里是<em>手工构造</em>的, 不注入容器里那个。理由是 sink 必须可控: 容器里注册的
 * 是录制型 sink, 它从不失败, 而"失败之后会怎样"正是这个类要证明的全部。用真容器那个的话,
 * 失败分支一次都跑不到。
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class OutboxRelayTest {

    private static final String APP = "com.luxera.tictactoe";
    private static final String RESOURCE = "game://session/outbox-" + UUID.randomUUID();
    private static final String EVENT_TYPE = "game.move";

    @Autowired
    OutboxEventStore store;

    @Autowired
    SubscriptionService subscriptionService;

    @Autowired
    LapOutboxRepository outbox;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    ManifestRegistry manifests;

    @Autowired
    ObjectMapper objectMapper;

    // ─────────────────────────── 落库 ───────────────────────────

    @Test
    void anInboxSubscriptionGetsARowThatTheRelayThenDelivers() {
        SubscriptionRecord subscription = subscribe(SubscriptionRequest.MODE_INBOX);
        RecordingSink sink = new RecordingSink();
        OutboxRelay relay = relay(sink, 5);

        List<String> enqueued = store.enqueue(manifest(), List.of(move("e-1")));

        assertEquals(List.of("e-1"), enqueued, "落进收件箱的事件 id 要回报给调用方");
        assertEquals(1, relay.pendingCount(), "落库即 PENDING, 投递是另一件事");

        assertEquals(1, relay.deliverPending());

        assertEquals(List.of("e-1"), sink.ids(), "sink 收到的是原来那条事件");
        assertEquals(0, relay.pendingCount());
        assertEquals(OutboxEventRecord.STATUS_DELIVERED, row("e-1", subscription).getStatus());
        assertNotNull(relay.lastDeliveredAt(subscription.getId()),
                "投出之后订阅自己的 lastDeliveredAt 要跟上 —— 排障要靠它回答'这个订阅还活着吗'");
    }

    /**
     * {@code idTemplate} 铸出的确定性 id 在这里兑现: 应用重启后重发同一条事件,
     * 主键把它挡在第二行之外, 而不是投两遍。
     */
    @Test
    void enqueueingTheSameEventTwiceWritesOneRow() {
        subscribe(SubscriptionRequest.MODE_INBOX);

        store.enqueue(manifest(), List.of(move("e-dup")));
        List<String> second = store.enqueue(manifest(), List.of(move("e-dup")));

        assertTrue(second.isEmpty(), "第二次什么都没落 —— 这不是错误, 是去重生效");
        assertEquals(1, relay(new RecordingSink(), 5).pendingCount());
    }

    /**
     * {@code INBOX} 与 {@code SINK} 是两条出口, 不是"完整版与降级版"。
     * 只有 SINK 订阅者时不该在收件箱里留下任何东西 —— 否则每条事件都会白落一行库。
     */
    @Test
    void aSinkOnlySubscriptionWritesNoRowAtAll() {
        subscribe(SubscriptionRequest.MODE_SINK);

        List<String> enqueued = store.enqueue(manifest(), List.of(move("e-sink")));

        assertTrue(enqueued.isEmpty());
        assertEquals(0, relay(new RecordingSink(), 5).pendingCount());
    }

    /** 没人订阅这件事时, 连一行都不该有。 */
    @Test
    void anUnsubscribedEventTypeWritesNoRow() {
        subscribe(SubscriptionRequest.MODE_INBOX);   // 只订了 game.move

        store.enqueue(manifest(), List.of(event("e-other", "game.created")));

        assertEquals(0, relay(new RecordingSink(), 5).pendingCount());
    }

    // ─────────────────────────── 投递失败 ───────────────────────────

    @Test
    void aFailedDeliveryStaysPendingAndIsRetriedNextRound() {
        SubscriptionRecord subscription = subscribe(SubscriptionRequest.MODE_INBOX);
        OutboxRelay relay = relay(new FailingSink(), 5);
        store.enqueue(manifest(), List.of(move("e-retry")));

        assertEquals(0, relay.deliverPending(), "投不出去就是 0, 不该假装成功");

        OutboxEventRecord row = row("e-retry", subscription);
        assertEquals(OutboxEventRecord.STATUS_PENDING, row.getStatus(), "留在原地等下一轮");
        assertEquals(1, row.getAttempts());
        assertNotNull(row.getLastError(), "要留下失败原因 —— 一片空白是最难查的那种失败");

        relay.deliverPending();
        assertEquals(2, row("e-retry", subscription).getAttempts());
    }

    /**
     * 试满之后是 {@code DEAD}: 终态, 而且需要有人来看。
     * 一直重试到天荒地老会把日志刷满, 并且让"这条事件到底送没送到"永远没有答案。
     */
    @Test
    void aSinkThatNeverRecoversEndsUpDeadRatherThanRetryingForever() {
        SubscriptionRecord subscription = subscribe(SubscriptionRequest.MODE_INBOX);
        OutboxRelay relay = relay(new FailingSink(), 2);   // 上限压到 2, 免得测试跑五轮
        store.enqueue(manifest(), List.of(move("e-dead")));

        relay.deliverPending();
        assertEquals(OutboxEventRecord.STATUS_PENDING,
                row("e-dead", subscription).getStatus(), "第一次失败还只是重试");

        relay.deliverPending();

        OutboxEventRecord row = row("e-dead", subscription);
        assertEquals(OutboxEventRecord.STATUS_DEAD, row.getStatus());
        assertEquals(2, row.getAttempts());
        assertEquals(0, relay.pendingCount(), "DEAD 不再是待投, 否则下一轮还会去撞同一个墙");
    }

    /** 判死之后不再重投 —— 否则 DEAD 只是换了个名字的 PENDING。 */
    @Test
    void aDeadRowIsNotRetried() {
        subscribe(SubscriptionRequest.MODE_INBOX);
        RecordingSink sink = new RecordingSink();
        store.enqueue(manifest(), List.of(move("e-stay-dead")));
        relay(new FailingSink(), 1).deliverPending();      // 一轮就判死

        assertEquals(0, relay(sink, 5).deliverPending());
        assertTrue(sink.ids().isEmpty());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private SubscriptionRecord subscribe(String mode) {
        return subscriptionService.subscribe(agent(), UUID.randomUUID().toString(),
                new SubscriptionRequest(RESOURCE, List.of(EVENT_TYPE), mode, null));
    }

    private ApplicationManifest manifest() {
        return manifests.published(APP).orElseThrow();
    }

    private ApplicationEvent move(String eventId) {
        return event(eventId, EVENT_TYPE);
    }

    private ApplicationEvent event(String eventId, String type) {
        return new ApplicationEvent(eventId, type, APP, RESOURCE, Instant.now(),
                objectMapper.createObjectNode().put("position", 4));
    }

    private OutboxEventRecord row(String eventId, SubscriptionRecord subscription) {
        String id = ManifestCatalogueSync.sha256(eventId + "@" + subscription.getId());
        return outbox.findById(id).orElseThrow();
    }

    private OutboxRelay relay(ApplicationEventSink sink, int maxAttempts) {
        return new OutboxRelay(outbox, subscriptions, List.of(sink), objectMapper, 50, maxAttempts);
    }

    private static ResolvedPrincipal agent() {
        String companionId = "dh-" + UUID.randomUUID();
        return new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId,
                companionId, null, "corr-" + UUID.randomUUID(), ResolvedPrincipal.SOURCE_INTERNAL);
    }

    private static final class RecordingSink implements ApplicationEventSink {
        private final List<String> delivered = new ArrayList<>();

        @Override
        public void emit(ApplicationEvent event) {
            delivered.add(event.id());
        }

        List<String> ids() {
            return delivered;
        }
    }

    private static final class FailingSink implements ApplicationEventSink {
        @Override
        public void emit(ApplicationEvent event) {
            throw new IllegalStateException("远端不可达");
        }
    }
}
