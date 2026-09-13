package com.luxera.companion.application;

import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.spi.ApplicationEventSink;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块测试里的"数字人那扇门"。
 *
 * <p>{@link ApplicationEventSink} 的实现永久属于 digital-human-platform, 所以本模块的测试
 * 只能自己拿一个记录型的 —— 这也顺带证明了应用平台发射事件时<b>不需要</b>数字人在场。
 */
public class RecordingApplicationEventSink implements ApplicationEventSink {

    private final List<ApplicationEvent> events = new ArrayList<>();

    @Override
    public void emit(ApplicationEvent event) {
        events.add(event);
    }

    public List<ApplicationEvent> events() {
        return List.copyOf(events);
    }

    public List<ApplicationEvent> eventsOfType(String type) {
        return events.stream().filter(e -> type.equals(e.type())).toList();
    }

    public void clear() {
        events.clear();
    }
}
