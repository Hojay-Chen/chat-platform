package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.SubscriptionRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.SubscriptionRepository;
import com.luxera.companion.contracts.application.ResourceUriPattern;
import com.luxera.companion.contracts.application.SubscriptionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * LAP v1: 订阅 —— 订阅者说"这一类资源上的这一类事件我要", 而不是轮询。
 *
 * <p><b>两种投递模式, 各有各的出口。</b>{@code SINK} 是<em>发射前的过滤器</em>: 事件投递前按
 * URI pattern 与事件类型筛一遍, 命中的进进程内直投。{@code INBOX} 是<em>持久投递</em>:
 * 命中的事件在业务事务内落进 {@code lap_outbox}, 由 {@code ApplicationOutboxRelayJob} 重试
 * 直到送达或判死 —— 所以订阅者关掉进程再回来, 消息还在。
 *
 * <p>一条订阅只走其中一条路: {@code LapEventPublisher} 按本类的 {@code inboxSubscribersFor}
 * 先落 outbox, 再让直投跳过已经落库的那些事件。于是"既要重试又要直投"不会变成发两遍。
 *
 * <p><b>它不是调度器。</b>"15:00 提醒我"是提醒应用自己的到期任务({@code ReminderDispatchJob}),
 * 不是订阅。把两者混为一谈会做出一个从不提醒任何人的 Agent: 订阅只在"有事件发生"时起作用,
 * 而"到点了"不是事件, 是时间。
 */
@Slf4j
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;

    public SubscriptionService(SubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Transactional
    public SubscriptionRecord subscribe(ResolvedPrincipal principal,
                                        String sessionId,
                                        SubscriptionRequest request) {
        if (request == null) {
            throw new SessionException("SUBSCRIPTION_REQUIRED", "缺少订阅请求");
        }
        String mode = request.deliveryMode();
        SubscriptionRecord row = new SubscriptionRecord();
        row.setSessionId(sessionId);
        row.setPrincipalType(principal.typeName());
        row.setPrincipalId(principal.principalId());
        row.setResourceUriPattern(request.resourceUriPattern() == null
                ? "**" : request.resourceUriPattern());
        row.setEventTypes(request.eventTypes().isEmpty() ? "" : String.join(",", request.eventTypes()));
        row.setDeliveryMode(mode);
        row.setStatus(SubscriptionRecord.STATUS_ACTIVE);
        row.setExpiresAt(request.expiresAt());
        return subscriptions.save(row);
    }

    /** 这个 (资源, 事件类型) 有没有活跃订阅者。 */
    public boolean matchesAny(String resourceUri, String eventType) {
        return !matching(resourceUri, eventType, null).isEmpty();
    }

    /**
     * 该 (资源, 事件类型) 上的 <b>INBOX</b> 订阅者 —— {@code OutboxEventStore} 用它决定
     * 这条事件要落进谁的收件箱。
     *
     * <p>刻意与 {@code matchesAny} 分开: "有没有人订"和"谁要持久的一份"是两个问题,
     * 混在一起的话, 一条只有 SINK 订阅者的事件会被错当成需要落库。
     */
    public List<SubscriptionRecord> inboxSubscribersFor(String resourceUri, String eventType) {
        return matching(resourceUri, eventType, SubscriptionRequest.MODE_INBOX);
    }

    private List<SubscriptionRecord> matching(String resourceUri, String eventType, String deliveryMode) {
        List<SubscriptionRecord> out = new java.util.ArrayList<>();
        for (SubscriptionRecord row : subscriptions.findByStatus(SubscriptionRecord.STATUS_ACTIVE)) {
            if (!row.active()) {
                continue;
            }
            if (deliveryMode != null && !deliveryMode.equals(row.getDeliveryMode())) {
                continue;
            }
            if (!ResourceUriPattern.matches(row.getResourceUriPattern(), resourceUri)) {
                continue;
            }
            String types = row.getEventTypes();
            if (types == null || types.isBlank() || List.of(types.split(",")).contains(eventType)) {
                out.add(row);
            }
        }
        return out;
    }

    @Transactional
    public void revoke(String subscriptionId, ResolvedPrincipal principal) {
        subscriptions.findById(subscriptionId).ifPresent(row -> {
            if (!row.getPrincipalType().equals(principal.typeName())
                    || !row.getPrincipalId().equals(principal.principalId())) {
                throw new SessionException("NOT_SUBSCRIPTION_OWNER", "不是这条订阅的订阅者");
            }
            row.setStatus(SubscriptionRecord.STATUS_REVOKED);
            subscriptions.save(row);
        });
    }

    public List<SubscriptionRecord> of(ResolvedPrincipal principal) {
        return subscriptions.findByPrincipalTypeAndPrincipalIdAndStatus(
                principal.typeName(), principal.principalId(), SubscriptionRecord.STATUS_ACTIVE);
    }
}
