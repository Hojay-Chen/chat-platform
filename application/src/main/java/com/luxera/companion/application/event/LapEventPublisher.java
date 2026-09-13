package com.luxera.companion.application.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.outbox.OutboxEventStore;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.spi.ApplicationEventSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1: 应用事件出平台的唯一出口 —— v2 起它同时是<em>平台事件</em>的出口(见
 * {@link #publishPlatform})。
 *
 * <p><b>三道闸, 缺一不可</b>:
 * <ol>
 *   <li><b>类型级</b>(这里) —— 只有 manifest 里标了 {@code triggersAgent: true} 的事件会被转发。
 *       应用不能自己决定"叫醒数字人", 那是它的 manifest 在<em>发布时</em>就申明过的事。
 *       没声明的事件照常进 {@code ActionResponse.events}(调用方看得见), 只是不推向 DH。</li>
 *   <li><b>实例级</b>(这里 + DH 各查一次) —— {@code data.agentTrigger} 说这一次具体要不要。
 *       井字棋的 {@code game.move} 类型上标了 true, 但轮到谁、局是否终了决定了某一步并不需要
 *       叫醒任何人。这里查是为了不做无用功, DH 那边再查一次是防御 —— 那边是独立消费者。</li>
 *   <li><b>路由</b>({@link AgentRouteResolver}) —— 应用说"还有谁该知道这件事", 平台答"其中谁
 *       是数字人", 答得出来才把 {@code data.companionId} 盖上并投递。
 *       <b>这是应用里不出现 Human / Agent 分支的关键</b>: 它只陈述自己的对局者是谁。</li>
 * </ol>
 *
 * <p><b>投递在事务提交之后</b>, 由 {@code afterCommit} 保证。在事务内投递的话, 数字人可能读到
 * 一个随后回滚的状态, 然后自信地回应一步根本没发生的棋。没有事务时立即投递 —— 那种情况下
 * 没有"以后"可言。
 *
 * <p><b>出口有两条, 而且只有两条。</b>
 * <ul>
 *   <li><b>直投</b>(下面的 {@code deliver}) —— 事务提交之后, 进程内交给 sink。快, 但不保证:
 *       投递失败只写一条 WARN, 没有重试;</li>
 *   <li><b>收件箱</b>({@code OutboxEventStore} + {@code ApplicationOutboxRelayJob}) ——
 *       在<em>业务事务之内</em>落一行, 由 relay 慢慢投, 失败重试、试满 DEAD。慢一点, 但不丢。</li>
 * </ul>
 * 一条事件如果已经被收件箱接走, 就不再直投 —— 见 {@link #publishAfterCommit}。
 *
 * <p>投递失败<em>不影响</em>动作结果: 动作已经提交了, 数字人没接住这条事件是它自己的问题,
 * 不该让一次成功的落子变成 500。
 */
@Slf4j
@Service
public class LapEventPublisher {

    private final List<ApplicationEventSink> sinks;
    private final AgentRouteResolver router;
    private final OutboxEventStore outbox;
    private final ObjectMapper objectMapper;

    public LapEventPublisher(List<ApplicationEventSink> sinks,
                             AgentRouteResolver router,
                             OutboxEventStore outbox,
                             ObjectMapper objectMapper) {
        this.sinks = List.copyOf(sinks);
        this.router = router;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * 事件出平台的唯一入口: <b>先落收件箱(同事务), 再在事务提交之后直投。</b>
     *
     * <p>两条路一起走是有讲究的, 顺序不能反:
     * <ol>
     *   <li>{@code outbox.enqueue} 在<em>调用方的业务事务里</em>执行 —— 它写下的行与业务同生共死,
     *       这是"不丢"的唯一来源;</li>
     *   <li>直投仍然挂在 {@code afterCommit} 上 —— 它换来的是"快", 代价是不保证;</li>
     *   <li>已经被收件箱接走的事件<em>不再直投</em>: 否则同一条步骤会走两遍, 数字人会对着同一步
     *       棋动两次手。持久路径赢, 因为它至少不会少投。</li>
     * </ol>
     */
    public void publishAfterCommit(ApplicationManifest manifest, List<ApplicationEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<String> inboxed = outbox.enqueue(manifest, events);
        List<ApplicationEvent> forward = forwardable(manifest, events).stream()
                .filter(event -> !inboxed.contains(event.id()))
                .toList();
        if (forward.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(forward);
                }
            });
        } else {
            deliver(forward);
        }
    }

    /** 平台自己的维护任务(回收器、测试)用: 立即投递, 不经事务同步。 */
    public void publishNow(ApplicationManifest manifest, List<ApplicationEvent> events) {
        deliver(forwardable(manifest, events));
    }

    /**
     * LAP v2: <b>平台事件的发射通道</b> —— 不查 {@code triggersAgent}, 也不做路由。
     *
     * <p>为什么这里必须开一条与 {@link #publishAfterCommit} 不同的路: 那两道闸(类型级
     * {@code triggersAgent} + 实例级 {@code agentTrigger})是防<em>应用</em>的 —— 应用不该
     * 自己决定"叫醒数字人"。而平台自己的事件({@code APPLICATION_INVITATION}: "有人邀请你了",
     * 计划修正 2)恰恰<em>就该</em>叫醒数字人, 且它不属于任何 manifest —— manifest 是应用作者
     * 写的, 平台的意图不能寄存在应用作者的笔下。
     *
     * <p><b>路由也不经过 {@link AgentRouteResolver}。</b>那条路由回答的问题是"这条事件挂着的
     * 会话里有哪些 AGENT 参与者" —— 而邀请事件收件的是一个<em>还没进来</em>的人(它正是被邀请
     * 才有机会进来的)。问"他在不在这场里", 答案必然是没有。于是收件人由铸造调用方显式点名,
     * {@code data.companionId} 在铸好那一刻就已经盖上 —— 平台直接投给这个人, 而不是查一遍
     * 名单再投。这并不是绕过安全检查: 点名的人只能是会话主人({@code InvitationService.mint}
     * 的 {@code requireOwned}), 而收不收数字人那边自己决定(accept / reject / ignore)。
     *
     * <p>实例级那道闸({@code data.agentTrigger})不查是同样的理由: 邀请事件的整个载荷就是
     * "这一次该叫醒谁", 没有比它更细的一级了。
     */
    public void publishPlatform(List<ApplicationEvent> events) {
        if (events == null || events.isEmpty() || sinks.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliverStamped(events);
                }
            });
        } else {
            deliverStamped(events);
        }
    }

    /** 平台事件已被点名, 直接投给 sink —— 收件人在 {@code data.companionId} 上。 */
    private void deliverStamped(List<ApplicationEvent> events) {
        for (ApplicationEvent event : events) {
            for (ApplicationEventSink sink : sinks) {
                try {
                    sink.emit(event);
                } catch (Exception e) {
                    log.warn("[LapEventPublisher] 平台事件投递失败 {} → {}: {}",
                            event.id(), sink.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    private List<ApplicationEvent> forwardable(ApplicationManifest manifest, List<ApplicationEvent> events) {
        if (events == null || events.isEmpty() || sinks.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .filter(event -> triggersAgent(manifest, event.type()))
                .filter(LapEventPublisher::wantedByInstance)
                .toList();
    }

    private void deliver(List<ApplicationEvent> events) {
        for (ApplicationEvent event : events) {
            Optional<AgentRouteResolver.Route> route = router.resolve(event.source(), event);
            if (route.isEmpty()) {
                log.debug("[LapEventPublisher] {} 没有可投递的数字人, 事件不出平台: {}", event.type(), event.id());
                continue;
            }
            ApplicationEvent routed = withRouting(event, route.get());
            for (ApplicationEventSink sink : sinks) {
                try {
                    sink.emit(routed);
                } catch (Exception e) {
                    log.warn("[LapEventPublisher] 事件投递失败 {} → {}: {}",
                            routed.id(), sink.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 把路由结果盖进 {@code data}。应用的原始 payload 一个字节都不动 —— 只加两个平台才知道的键
     * ({@code companionId} / {@code userId}), 于是 {@code ActionResponse.events} 里应用自己看见的
     * 那份事件与投递给数字人的那份内容一致, 只是后者多了收件人。
     */
    private ApplicationEvent withRouting(ApplicationEvent event, AgentRouteResolver.Route route) {
        JsonNode data = event.data();
        ObjectNode enriched = data != null && data.isObject()
                ? ((ObjectNode) data).deepCopy()
                : objectMapper.createObjectNode();
        enriched.put("companionId", route.companionId());
        if (route.userId() != null) {
            enriched.put("userId", route.userId());
        }
        return new ApplicationEvent(event.id(), event.type(), event.source(), event.target(),
                event.occurredAt(), enriched);
    }

    /** {@code data.agentTrigger}, 缺省 false —— 见类注释的第 2 道闸。 */
    private static boolean wantedByInstance(ApplicationEvent event) {
        JsonNode data = event.data();
        return data != null && data.path("agentTrigger").asBoolean(false);
    }

    private static boolean triggersAgent(ApplicationManifest manifest, String eventType) {
        return manifest.events().stream()
                .anyMatch(decl -> decl.type().equals(eventType) && decl.triggersAgent());
    }
}
