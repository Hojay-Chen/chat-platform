package com.luxera.companion.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.domain.OutboxEventRecord;
import com.luxera.companion.application.domain.SubscriptionRecord;
import com.luxera.companion.application.repository.LapOutboxRepository;
import com.luxera.companion.application.repository.SubscriptionRepository;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.spi.ApplicationEventSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LAP v1: <b>收件箱的投递器</b> —— 把 {@code lap_outbox} 里 PENDING 的行交给平台的 sink。
 *
 * <p>它与直投路径({@code LapEventPublisher.deliver})是同一件事的两个版本, 差别只有一条:
 * <b>这里的起点是一行已经提交的数据, 所以进程在任何位置死掉都不会丢</b>。重启之后 PENDING 还在,
 * 下一次扫描接着投。
 *
 * <p><b>投递语义是 at-least-once, 不是 exactly-once。</b> "投出去了但标记没写成"这种情况一定
 * 会发生(进程正好死在两者之间), 于是同一条事件会被投第二遍。这不是缺陷而是选择的代价 ——
 * 要消除它需要接收方参与两阶段确认, 而那套复杂度在这个平台上没有对应的收益。
 * 真正挡住重复的是<em>事件 id 的确定性</em>: {@code triggersAgent} 的事件被强制要求
 * {@code idTemplate}, 数字人侧按 id 去重, 于是第二次投递是空操作。收件箱这一层保证的是
 * "不丢", 去重由事件 id 保证 —— 两件事必须分开做, 合起来做的那一种通常是两件都做不好。
 *
 * <p><b>它自己没有 {@code @Transactional}。</b> 每一条的终态回填由 Spring Data 的
 * {@code save} 各自成一事务, 投递本身(可能是一次跨进程调用)因此不在任何事务里 ——
 * 一个慢 sink 不该把数据库连接连同行锁一起握在手上。
 */
@Slf4j
@Service
public class OutboxRelay {

    private final LapOutboxRepository outbox;
    private final SubscriptionRepository subscriptions;
    private final List<ApplicationEventSink> sinks;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(LapOutboxRepository outbox,
                       SubscriptionRepository subscriptions,
                       List<ApplicationEventSink> sinks,
                       ObjectMapper objectMapper,
                       @Value("${app.lap.outbox.batch-size:50}") int batchSize,
                       @Value("${app.lap.outbox.max-attempts:5}") int maxAttempts) {
        this.outbox = outbox;
        this.subscriptions = subscriptions;
        this.sinks = List.copyOf(sinks);
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** 投一轮, 返回成功投出的条数。 */
    public int deliverPending() {
        List<OutboxEventRecord> batch = outbox.findByStatusOrderByCreatedAtAsc(
                OutboxEventRecord.STATUS_PENDING, PageRequest.of(0, Math.max(1, batchSize)));
        if (batch.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (OutboxEventRecord row : batch) {
            if (deliver(row)) {
                delivered++;
            }
        }
        if (delivered > 0) {
            log.debug("[OutboxRelay] 投出 {} 条持久事件", delivered);
        }
        return delivered;
    }

    private boolean deliver(OutboxEventRecord row) {
        try {
            ApplicationEvent event = objectMapper.readValue(row.getPayload(), ApplicationEvent.class);
            for (ApplicationEventSink sink : sinks) {
                sink.emit(event);
            }
            markDelivered(row);
            return true;
        } catch (Exception e) {
            markFailed(row, e);
            return false;
        }
    }

    private void markDelivered(OutboxEventRecord row) {
        LocalDateTime now = LocalDateTime.now();
        row.setStatus(OutboxEventRecord.STATUS_DELIVERED);
        row.setDeliveredAt(now);
        row.setLastError(null);
        outbox.save(row);
        if (row.getSubscriptionId() != null) {
            subscriptions.findById(row.getSubscriptionId()).ifPresent(subscription -> {
                subscription.setLastDeliveredAt(now);
                subscriptions.save(subscription);
            });
        }
    }

    /**
     * 失败不回滚、不丢弃: 留在 PENDING 等下一轮 —— 除非已经试满。
     * {@code DEAD} 是终态, 而且是一条 ERROR 日志: 一条投不出去的事件需要有人来看,
     * 而不是被重试到天荒地老把日志刷满。
     */
    private void markFailed(OutboxEventRecord row, Exception e) {
        row.setAttempts(row.getAttempts() + 1);
        row.setLastError(clip(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        if (row.getAttempts() >= maxAttempts) {
            row.setStatus(OutboxEventRecord.STATUS_DEAD);
            log.error("[OutboxRelay] 事件 {} 投递 {} 次仍失败, 标记 DEAD: {}",
                    row.getEventId(), row.getAttempts(), row.getLastError());
        } else {
            log.warn("[OutboxRelay] 事件 {} 第 {} 次投递失败, 下轮重试: {}",
                    row.getEventId(), row.getAttempts(), row.getLastError());
        }
        outbox.save(row);
    }

    private static String clip(String text) {
        return text != null && text.length() > 512 ? text.substring(0, 512) : text;
    }

    /** 给运维与断言用: 收件箱里还剩多少没投出去。 */
    public long pendingCount() {
        return outbox.countByStatus(OutboxEventRecord.STATUS_PENDING);
    }

    /** 给测试用: 某个订阅最近一次投递成功的时刻。 */
    public LocalDateTime lastDeliveredAt(String subscriptionId) {
        return subscriptions.findById(subscriptionId)
                .map(SubscriptionRecord::getLastDeliveredAt)
                .orElse(null);
    }
}
