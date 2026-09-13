package com.luxera.companion.application.manifest;

import com.luxera.companion.application.action.ActionHandlerKey;
import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.action.PendingActionRegistry;
import com.luxera.companion.application.spi.LapApplicationModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 启动时把每个内置应用的 manifest 从 classpath 读出来: <b>解析 → 校验 → 检查 handler 齐不齐
 * → 注册</b>。四步的顺序是刻意的 ——
 *
 * <ul>
 *   <li>解析/校验放在平台里统一做, 应用没有绕开的机会;</li>
 *   <li><b>handler 齐不齐在注册<em>之前</em>查</b>: manifest 里写了 {@code game.make_move}
 *       但代码里没有对应 handler, 是"发布了一个点了没反应的动作"。这种应用不该进注册表 ——
 *       报 {@code ACTION_HANDLER_MISSING} 让服务起不来, 而不是等用户点了才发现。</li>
 * </ul>
 *
 * <p>实现 {@link SmartInitializingSingleton} 而不是 {@code @PostConstruct}: 必须等所有
 * 应用 Bean 都建好、{@code LapApplicationModule} 列表完整之后再跑, 否则注册表可能少一半。
 */
@Slf4j
@Component
public class ManifestRegistrar implements SmartInitializingSingleton {

    private final ManifestParser parser;
    private final ManifestValidator validator;
    private final ManifestRegistry registry;
    private final ActionHandlerRegistry handlers;
    private final PendingActionRegistry pendingActions;
    private final ManifestCatalogueSync catalogue;
    private final ResourceLoader resourceLoader;
    private final List<LapApplicationModule> modules;

    public ManifestRegistrar(ManifestParser parser,
                             ManifestValidator validator,
                             ManifestRegistry registry,
                             ActionHandlerRegistry handlers,
                             PendingActionRegistry pendingActions,
                             ManifestCatalogueSync catalogue,
                             ResourceLoader resourceLoader,
                             List<LapApplicationModule> modules) {
        this.parser = parser;
        this.validator = validator;
        this.registry = registry;
        this.handlers = handlers;
        this.pendingActions = pendingActions;
        this.catalogue = catalogue;
        this.resourceLoader = resourceLoader;
        this.modules = List.copyOf(modules);
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (LapApplicationModule module : modules) {
            register(module);
        }
        log.info("[ManifestRegistrar] 已注册 {} 个应用, {} 个动作处理器",
                registry.applications().size(), handlers.size());
    }

    /** 单个应用的完整注册流程; 对测试也开放(让"缺 handler 就发布失败"可以被直接断言)。 */
    public ApplicationManifest register(LapApplicationModule module) {
        String json = read(module.manifestLocation());
        ApplicationManifest manifest = parser.parse(json);
        validator.validate(manifest);

        module.registerHandlers(handlers);
        module.registerPendingActions(pendingActions);

        String appId = manifest.applicationId();
        String version = manifest.version();
        for (ApplicationManifest.ActionDecl action : manifest.actions()) {
            if (!handlers.contains(appId, version, action.id())) {
                throw new IllegalStateException("ACTION_HANDLER_MISSING: " + action.id()
                        + " 在 manifest 里声明了, 但没有对应的 ActionHandler "
                        + ActionHandlerKey.of(appId, version, action.id()));
            }
        }
        registry.register(manifest);
        catalogue.sync(manifest, json);
        return manifest;
    }

    private String read(String location) {
        Resource resource = resourceLoader.getResource("classpath:" + location);
        if (!resource.exists()) {
            throw ManifestException.of("MANIFEST_NOT_FOUND", "找不到 manifest: classpath:" + location);
        }
        try (var in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw ManifestException.of("MANIFEST_READ_ERROR",
                    "读取 manifest 失败 classpath:" + location + ": " + e.getMessage());
        }
    }
}
