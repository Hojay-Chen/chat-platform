package com.luxera.companion.application.remote;

import com.luxera.companion.application.action.ActionHandlerKey;
import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestCatalogueSync;
import com.luxera.companion.application.manifest.ManifestException;
import com.luxera.companion.application.manifest.ManifestParser;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.manifest.ManifestValidator;
import com.luxera.companion.application.manifest.RuntimeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 启动时把<b>配置里声明的远端应用</b>注册进平台 —— {@code ManifestRegistrar} 的孪生兄弟。
 *
 * <p><b>为什么不和它合成一个类。</b> 两者回答的是同一个问题("这个 JVM 里有哪些应用"), 但答案的
 * 来源完全不同: 内置应用是"classpath 上有哪些 {@code LapApplicationModule} Bean", 远端应用是
 * "配置里指了哪些 manifest 文件"。合成一个的话, 那个类会同时需要 {@code List<LapApplicationModule>}
 * 与一个路径列表, 而它真正做的事只是"读一份 manifest, 把每个动作挂到一个 handler 上, 注册" ——
 * 这一份逻辑很短, 复制它比把两种来源的差异塞进一个方法的 if 里更清楚。
 *
 * <p><b>远端应用的 handler 由平台提供</b>, 所以"每个动作都要有 handler"那条发布前校验在这里
 * 自动成立 —— 这是 REMOTE 与 NATIVE 在宿主侧唯一的实质差别: 前者不需要应用作者提交任何代码,
 * 只需要一个能收 HTTP 的地址。
 *
 * <p>配置: {@code app.lap.remote-applications} 是逗号分隔的 classpath 位置。默认空 ——
 * 一个默认空列表比一个默认指向某台服务器的地址安全得多, 后者会让一台没有部署任何东西的机器
 * 在启动时才暴露出来。
 */
@Slf4j
@Component
public class RemoteApplicationRegistrar implements SmartInitializingSingleton {

    private final ManifestParser parser;
    private final ManifestValidator validator;
    private final ManifestRegistry registry;
    private final ActionHandlerRegistry handlers;
    private final ManifestCatalogueSync catalogue;
    private final RemoteApplicationInvoker invoker;
    private final ResourceLoader resourceLoader;
    private final List<String> manifestLocations;

    public RemoteApplicationRegistrar(ManifestParser parser,
                                      ManifestValidator validator,
                                      ManifestRegistry registry,
                                      ActionHandlerRegistry handlers,
                                      ManifestCatalogueSync catalogue,
                                      RemoteApplicationInvoker invoker,
                                      ResourceLoader resourceLoader,
                                      @Value("${app.lap.remote-applications:}") String manifestLocations) {
        this.parser = parser;
        this.validator = validator;
        this.registry = registry;
        this.handlers = handlers;
        this.catalogue = catalogue;
        this.invoker = invoker;
        this.resourceLoader = resourceLoader;
        this.manifestLocations = Arrays.stream(String.valueOf(manifestLocations).split(","))
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .toList();
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String location : manifestLocations) {
            register(location);
        }
        if (!manifestLocations.isEmpty()) {
            log.info("[RemoteRegistrar] 注册了 {} 个远端应用", manifestLocations.size());
        }
    }

    /** 单个远端应用的注册流程; 对测试开放, 免得只为断言它而起一整个 Spring 上下文。 */
    public ApplicationManifest register(String location) {
        String json = read(location);
        ApplicationManifest manifest = parser.parse(json);
        validator.validate(manifest);
        if (manifest.runtime() == null || manifest.runtime().type() != RuntimeType.REMOTE) {
            // 配置指了一个 NATIVE manifest 是部署错误, 而不是"那就当内置的处理吧":
            // 内置应用的 handler 只可能来自代码, 平台这边凭空造不出来。
            throw ManifestException.of("REMOTE_MANIFEST_REQUIRED",
                    location + " 的 runtime.type 是 "
                            + (manifest.runtime() == null ? "null" : manifest.runtime().type())
                            + ", 但它是作为远端应用配置的");
        }
        String appId = manifest.applicationId();
        String version = manifest.version();
        for (ApplicationManifest.ActionDecl action : manifest.actions()) {
            handlers.register(ActionHandlerKey.of(appId, version, action.id()),
                    new RemoteActionHandler(manifest, invoker));
        }
        registry.register(manifest);
        catalogue.sync(manifest, json);
        return manifest;
    }

    private String read(String location) {
        Resource resource = resourceLoader.getResource("classpath:" + location);
        if (!resource.exists()) {
            throw ManifestException.of("MANIFEST_NOT_FOUND", "找不到远端应用的 manifest: classpath:" + location);
        }
        try (var in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw ManifestException.of("MANIFEST_READ_ERROR",
                    "读取远端 manifest 失败 classpath:" + location + ": " + e.getMessage());
        }
    }

    /** 配置里声明了几个远端应用 —— 供运维与测试问。 */
    public List<String> manifestLocations() {
        return manifestLocations;
    }
}
