package com.luxera.companion.application.action;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code (applicationId, version, actionId) → handler} 的注册表。
 *
 * <p>重复注册同一把键是<em>错误的</em>, 所以直接抛异常而不是覆盖: 覆盖只会在运行期表现为
 * "另一个应用的动作跑的是我的代码", 而抛异常在启动时就报出来。
 */
@Slf4j
@Component
public class ActionHandlerRegistry {

    private final Map<ActionHandlerKey, ActionHandler> handlers = new ConcurrentHashMap<>();

    public void register(ActionHandlerKey key, ActionHandler handler) {
        if (key == null || handler == null) return;
        ActionHandler previous = handlers.putIfAbsent(key, handler);
        if (previous != null) {
            throw new IllegalStateException("动作处理器重复注册: " + key
                    + " —— 同一个 (应用, 版本, 动作) 只能有一个实现");
        }
        log.debug("[ActionHandlerRegistry] 注册 {}", key);
    }

    public Optional<ActionHandler> find(String applicationId, String version, String actionId) {
        return find(new ActionHandlerKey(applicationId, version, actionId));
    }

    /** 网关拿到的本来就是一把键({@code ActionResolution.handlerKey()}), 不必拆成三段再拼回来。 */
    public Optional<ActionHandler> find(ActionHandlerKey key) {
        return key == null ? Optional.empty() : Optional.ofNullable(handlers.get(key));
    }

    public boolean contains(String applicationId, String version, String actionId) {
        return handlers.containsKey(new ActionHandlerKey(applicationId, version, actionId));
    }

    public List<ActionHandlerKey> keys() {
        return List.copyOf(handlers.keySet());
    }

    public int size() {
        return handlers.size();
    }

    /** 仅供测试清理。 */
    public void clear() {
        handlers.clear();
    }
}
