package com.luxera.companion.application.action;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LAP v1: {@link PendingActionProvider} 的注册表, 键是 {@link ApplicationKey}(应用 + 版本)。
 *
 * <p>和 {@link ActionHandlerRegistry} 一样用 {@code putIfAbsent} 并在重复时抛错, 不静默覆盖 ——
 * "哪个版本的回答生效"这件事必须是确定的, 让后注册的悄悄盖掉先注册的, 症状会在很远的地方
 * 显现(某个老会话突然按新规则回答问题)。
 */
@Component
public class PendingActionRegistry {

    private final Map<ApplicationKey, PendingActionProvider> providers = new ConcurrentHashMap<>();

    public void register(String applicationId, String version, PendingActionProvider provider) {
        ApplicationKey key = ApplicationKey.of(applicationId, version);
        PendingActionProvider previous = providers.putIfAbsent(key, provider);
        if (previous != null) {
            throw new IllegalStateException(
                    "DUPLICATE_PENDING_ACTION_PROVIDER: " + key + " 已经注册过");
        }
    }

    public Optional<PendingActionProvider> find(String applicationId, String version) {
        return Optional.ofNullable(providers.get(ApplicationKey.of(applicationId, version)));
    }

    public int size() {
        return providers.size();
    }

    public void clear() {
        providers.clear();
    }
}
