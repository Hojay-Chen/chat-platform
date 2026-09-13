package com.luxera.companion.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.domain.OutboxEventRecord;
import com.luxera.companion.application.domain.SubscriptionRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestCatalogueSync;
import com.luxera.companion.application.repository.LapOutboxRepository;
import com.luxera.companion.application.session.SubscriptionService;
import com.luxera.companion.contracts.application.ApplicationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LAP v1: 把事件落进 INBOX 订阅者的收件箱 —— <b>在业务事务里, 一次一条, 落不下去就一起回滚。</b>
 *
 * <p>它由 {@code LapEventPublisher} 在<em>动作事务之内</em>调用, 这一点是整个持久投递的全部
 * 依据: 行与业务同生共死, 于是"业务提交了但信没留下"这个窗口根本不存在(对比直投路径, 那个窗口
 * 是两个动作之间的一段时间)。所以这个类<em>没有</em> {@code @Transactional} —— 加了会开一个
 * 新事务, 把上面那句话废掉。
 *
 * <p><b>主键冲突不是错误, 是去重生效。</b> 应用的 {@code idTemplate} 保证同一条事件有确定的 id;
 * 重启后重发同一条事件时, 主键会把第二行挡下来。这里必须吞掉 {@code DataIntegrityViolation} ——
 * 但吞掉之后<em>事务已经脏了</em>, 所以插入走的是"先查后插 + 冲突再查"而不是直接 save 靠异常兜底:
 * 在同一个事务里吃掉一次约束冲突, 会让后续任何一次 flush 都失败。
 */
@Slf4j
@Service
public class OutboxEventStore {

    private final LapOutboxRepository outbox;
    private final SubscriptionService subscriptions;
    private final ObjectMapper objectMapper;

    public OutboxEventStore(LapOutboxRepository outbox,
                            SubscriptionService subscriptions,
                            ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
    }

    /**
     * 为每条事件找出 INBOX 订阅者, 各落一行。
     *
     * @return 已经落进收件箱的<b>事件 id</b> —— 调用方靠它把这条事件从直投路径上摘掉,
     *         否则同一个数字人会收到两遍同一步棋(直投一遍, relay 一遍)。
     */
    public List<String> enqueue(ApplicationManifest manifest, List<ApplicationEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<String> enqueued = new ArrayList<>();
        for (ApplicationEvent event : events) {
            List<SubscriptionRecord> inbox = subscriptions.inboxSubscribersFor(event.target(), event.type());
            if (inbox.isEmpty()) {
                continue;
            }
            for (SubscriptionRecord subscription : inbox) {
                if (write(manifest, event, subscription)) {
                    enqueued.add(event.id());
                }
            }
        }
        return enqueued;
    }

    private boolean write(ApplicationManifest manifest, ApplicationEvent event, SubscriptionRecord subscription) {
        String id = ManifestCatalogueSync.sha256(event.id() + "@" + subscription.getId());
        if (outbox.findById(id).isPresent()) {
            // 同一条事件对同一条订阅已经有行了 —— idTemplate 的去重在这里生效, 不重复投递。
            return false;
        }
        OutboxEventRecord row = new OutboxEventRecord();
        row.setId(id);
        row.setEventId(event.id());
        row.setApplicationId(manifest.applicationId());
        row.setEventType(event.type());
        row.setTargetUri(event.target());
        row.setSubscriptionId(subscription.getId());
        row.setPrincipalType(subscription.getPrincipalType());
        row.setPrincipalId(subscription.getPrincipalId());
        row.setPayload(serialize(event));
        row.setStatus(OutboxEventRecord.STATUS_PENDING);
        try {
            outbox.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // 竞态: 两次投递同时走到了这里。并发的那个赢了, 这一条按去重处理 —— 但异常已经
            // 把事务标脏了, 只能往上抛, 由调用方重试整个动作(幂等键会保证它不重复执行)。
            log.warn("[Outbox] 收件箱条目并发写入冲突, 本次动作将重试: {}", id);
            throw e;
        }
        log.debug("[Outbox] {} 的事件 {} 落进 {} 的收件箱", event.type(), event.id(),
                subscription.getPrincipalId());
        return true;
    }

    private String serialize(ApplicationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("事件无法序列化进收件箱: " + e.getMessage(), e);
        }
    }
}
