package com.luxera.companion.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 伴侣事件总线(§二十七): 每 companion 一组 SSE 订阅者。
 * §17 升级: publish 时同步写入 {@link EventLogService}(SSE 游标), 断线重连可回放。
 * 瞬态事件(心跳/打字)不入日志 —— 回放它们没有意义。
 * 前端通过 GET /events 长连接订阅, 后端各模块 publish 事件实时推送。
 */
@Slf4j
@Component
public class CompanionEventBus {

    /** 瞬态事件: 不落日志(回放无意义) */
    private static final Set<String> TRANSIENT_EVENTS = Set.of("ping", "companion_typing");

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final EventLogService eventLogService;

    public CompanionEventBus(EventLogService eventLogService) {
        this.eventLogService = eventLogService;
    }

    /** 注册订阅: 返回当前连接数(调试/监控) */
    public int subscribe(String companionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.computeIfAbsent(companionId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        connectionCount.incrementAndGet();
        return connectionCount.get();
    }

    /**
     * 发布事件到某 companion 的所有订阅者(失败即移除)。
     * 非瞬态事件先落事件日志(拿 eventId 作为游标), 再推送给在线订阅者。
     */
    public void publish(String companionId, String event, Object data) {
        if (companionId == null) return;
        Object payload = data;
        if (!TRANSIENT_EVENTS.contains(event)) {
            Long eventId = eventLogService.append(companionId, event, toMap(data));
            if (eventId != null && data instanceof Map) {
                // 注入 eventId 供前端记录游标
                Map<String, Object> enriched = new LinkedHashMap<>((Map<String, Object>) data);
                enriched.put("eventId", eventId);
                payload = enriched;
            }
        }
        List<SseEmitter> list = subscribers.get(companionId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
                connectionCount.decrementAndGet();
            }
        }
    }

    /** 清理某连接(断连/超时) */
    public void unsubscribe(String companionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(companionId);
        if (list != null && list.remove(emitter)) {
            connectionCount.decrementAndGet();
        }
    }

    /** 向所有订阅者发心跳(EventStreamController 定时调用) */
    public void heartbeat() {
        Map<String, CopyOnWriteArrayList<SseEmitter>> snapshot = new ConcurrentHashMap<>(subscribers);
        snapshot.forEach((companionId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data("{}"));
                } catch (IOException | IllegalStateException e) {
                    unsubscribe(companionId, emitter);
                }
            }
        });
    }

    public int connectionCount() {
        return connectionCount.get();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object data) {
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", String.valueOf(data));
        return m;
    }
}
