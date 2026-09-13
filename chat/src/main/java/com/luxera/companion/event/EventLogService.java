package com.luxera.companion.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * §17 事件日志服务: 将事件落库供 SSE 游标回放。
 * 独立事务(REQUIRES_NEW): 即使事件发布失败也不影响业务主事务。
 */
@Slf4j
@Service
public class EventLogService {

    private final EventLogRepository repo;

    public EventLogService(EventLogRepository repo) {
        this.repo = repo;
    }

    /** 落一条事件日志, 返回日志 id(作为 SSE 游标)。payload 会被注入 eventId。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long append(String companionId, String event, Map<String, Object> payload) {
        try {
            EventLogEntry e = new EventLogEntry();
            e.setCompanionId(companionId);
            e.setEvent(event);
            e.setPayload(payload);
            EventLogEntry saved = repo.saveAndFlush(e);
            return saved.getId();
        } catch (Exception ex) {
            // 日志失败不能拖垮业务
            log.debug("[EventLog] 落库失败 companion={} event={}: {}", companionId, event, ex.getMessage());
            return null;
        }
    }

    /** 游标回放 */
    @Transactional(readOnly = true)
    public java.util.List<EventLogEntry> after(String companionId, long afterId) {
        return repo.findByCompanionIdAndIdGreaterThanOrderByIdAsc(companionId, afterId);
    }

    /** 新连接无游标时回放最近 N 条(供前端补漏) */
    @Transactional(readOnly = true)
    public java.util.List<EventLogEntry> recent(String companionId, int limit) {
        return repo.findRecent(companionId, org.springframework.data.domain.PageRequest.of(0, limit));
    }
}
