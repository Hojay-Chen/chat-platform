package com.luxera.companion.event;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * §17 事件日志(SSE 游标 + 世界事件骨架):
 * 每次 {@link CompanionEventBus#publish} 落一条(除心跳/打字等瞬态事件)。
 * id 单调递增 → 前端 Last-Event-ID 断线重连后回放, 消息永不丢。
 * 同时是 WorldEventEngine 的持久化基础(事件溯源)。
 */
@Entity
@Table(name = "event_log", indexes = {
        @Index(name = "idx_event_log_companion", columnList = "companion_id,id"),
        @Index(name = "idx_event_log_time", columnList = "occurred_at")
})
@Getter
@Setter
public class EventLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** 事件名: message_created / companion_message / user_message_status / ... */
    @Column(nullable = false, length = 48)
    private String event;

    @Convert(converter = StringMapConverter.class)
    @Column(name = "payload", columnDefinition = "text")
    private Map<String, Object> payload;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;
}
