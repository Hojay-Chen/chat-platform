package com.luxera.companion.event;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * 持久事件流(§二十七) + §17 SSE 游标:
 * 前端 GET /events 长连接, 携带 Last-Event-ID(或 ?after=) 断线重连后回放错过的消息。
 * 心跳每 25s(spring 内置 SseEmitter 默认 30s 超时, 这里用长超时+自管理心跳)。
 * 认证: JWT + 伴侣归属校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/companions/{companionId}/events")
public class EventStreamController {

    private static final long STREAM_TIMEOUT = 24 * 60 * 60 * 1000L; // 24h
    /** 无游标时回放最近条数(覆盖断连间隙) */
    private static final int REPLAY_LIMIT = 200;

    private final CompanionEventBus eventBus;
    private final CompanionDirectoryPort companionDirectory;
    private final CurrentUser currentUser;
    private final EventLogService eventLogService;

    public EventStreamController(CompanionEventBus eventBus, CompanionDirectoryPort companionDirectory,
                                 CurrentUser currentUser, EventLogService eventLogService) {
        this.eventBus = eventBus;
        this.companionDirectory = companionDirectory;
        this.currentUser = currentUser;
        this.eventLogService = eventLogService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String companionId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(value = "after", required = false) Long afterParam) {
        String userId = currentUser.requireUserId();
        companionDirectory.requireOwned(userId, companionId);

        long after = resolveAfter(lastEventId, afterParam);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT);

        // §17: 游标回放 —— 断线期间的事件先补发, 再订阅实时流
        if (after > 0) {
            replay(emitter, companionId, after);
        } else {
            // 无游标(首次连接): 回放最近若干条, 避免连接建立瞬间的竞态丢失
            replayRecent(emitter, companionId);
        }

        int conns = eventBus.subscribe(companionId, emitter);
        emitter.onCompletion(() -> eventBus.unsubscribe(companionId, emitter));
        emitter.onTimeout(() -> eventBus.unsubscribe(companionId, emitter));
        emitter.onError(e -> eventBus.unsubscribe(companionId, emitter));
        log.debug("事件流连接: companion={} after={} 当前连接数={}", companionId, after, conns);
        return emitter;
    }

    private static long resolveAfter(String lastEventId, Long afterParam) {
        if (afterParam != null && afterParam > 0) return afterParam;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException ignored) {
                // 非数字游标(如 Last-Event-ID 被代理改写) → 不回放
            }
        }
        return 0;
    }

    private void replay(SseEmitter emitter, String companionId, long after) {
        List<EventLogEntry> entries = eventLogService.after(companionId, after);
        for (EventLogEntry e : entries) {
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(e.getId()))
                        .name(e.getEvent()).data(e.getPayload()));
            } catch (IOException | IllegalStateException ex) {
                // 回放失败(客户端已断开) → 中止
                return;
            }
        }
    }

    private void replayRecent(SseEmitter emitter, String companionId) {
        List<EventLogEntry> entries = eventLogService.recent(companionId, REPLAY_LIMIT);
        for (EventLogEntry e : entries) {
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(e.getId()))
                        .name(e.getEvent()).data(e.getPayload()));
            } catch (IOException | IllegalStateException ex) {
                return;
            }
        }
    }

    /** 每 25s 向所有订阅者发心跳(防止代理/浏览器断开空闲连接) */
    @Scheduled(fixedRate = 25000, initialDelay = 25000)
    public void heartbeatJob() {
        try {
            eventBus.heartbeat();
        } catch (Exception e) {
            log.debug("事件流心跳异常: {}", e.getMessage());
        }
    }
}
