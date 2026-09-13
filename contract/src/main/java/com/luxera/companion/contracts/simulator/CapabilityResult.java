package com.luxera.companion.contracts.simulator;

import java.util.Map;

/**
 * V10 §4.3 Capability 执行结果。
 * 动作完成必须通过事件返回(action.completed), 这里只承载同步执行结果。
 */
public record CapabilityResult(boolean success, String message, Map<String, Object> data) {

    public static CapabilityResult ok(String message, Map<String, Object> data) {
        return new CapabilityResult(true, message, data);
    }

    public static CapabilityResult ok(String message) {
        return new CapabilityResult(true, message, Map.of());
    }

    public static CapabilityResult fail(String message) {
        return new CapabilityResult(false, message, Map.of());
    }

    public Object get(String key) {
        return data == null ? null : data.get(key);
    }

    public String str(String key) {
        Object v = get(key);
        return v == null ? null : v.toString();
    }
}
