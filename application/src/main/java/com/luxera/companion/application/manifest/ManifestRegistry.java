package com.luxera.companion.application.manifest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已注册 manifest 的目录 —— <b>平台里唯一"有哪些应用、能做什么"的真相来源</b>。
 *
 * <p>两级索引: {@code appId@version → manifest} 是全集(历史版本仍可解释),
 * {@code appId → 最新版本} 是发现链看到的那份。选最新版本用的是<em>版本号比较</em>,
 * 不是注册顺序 —— 启动顺序不该决定谁生效。
 *
 * <p>按动作/能力反查都返回<em>列表</em>: {@code game.make_move} 同时属于井字棋和五子棋,
 * 把它们合成一个才是 bug。
 */
@Slf4j
@Component
public class ManifestRegistry {

    private final Map<String, ApplicationManifest> byAppVersion = new ConcurrentHashMap<>();
    private final Map<String, String> latestVersionByApp = new ConcurrentHashMap<>();

    /** 注册一份 manifest。同一 (应用, 版本) 重复注册是启动配置错误, 直接拒绝。 */
    public void register(ApplicationManifest manifest) {
        String appId = manifest.applicationId();
        String version = manifest.version();
        String key = key(appId, version);
        if (byAppVersion.putIfAbsent(key, manifest) != null) {
            throw new IllegalStateException("manifest 重复注册: " + key);
        }
        latestVersionByApp.merge(appId, version, (a, b) -> compareVersions(a, b) >= 0 ? a : b);
        log.info("[ManifestRegistry] 注册应用 {} v{} ({} 个能力, {} 个动作)",
                appId, version, manifest.capabilities().size(), manifest.actions().size());
    }

    public Optional<ApplicationManifest> find(String applicationId, String version) {
        if (applicationId == null || version == null) return Optional.empty();
        return Optional.ofNullable(byAppVersion.get(key(applicationId, version)));
    }

    /** 发现链看到的那一版: 该应用已注册的最新版本。 */
    public Optional<ApplicationManifest> published(String applicationId) {
        if (applicationId == null) return Optional.empty();
        String version = latestVersionByApp.get(applicationId);
        return version == null ? Optional.empty() : find(applicationId, version);
    }

    /** 每个应用的最新版本, 按 appId 排序 —— 顺序稳定, 便于断言与展示。 */
    public List<ApplicationManifest> applications() {
        List<ApplicationManifest> out = new ArrayList<>();
        latestVersionByApp.keySet().stream().sorted().forEach(appId -> published(appId).ifPresent(out::add));
        return out;
    }

    /** 全部已注册版本(含历史)。 */
    public List<ApplicationManifest> allVersions() {
        return byAppVersion.values().stream()
                .sorted(Comparator.comparing(ApplicationManifest::applicationId)
                        .thenComparing(ApplicationManifest::version))
                .toList();
    }

    /**
     * 声明了该动作的应用。返回多于一说明调用方必须用 target 消歧 ——
     * 这正是 {@code game.make_move} 在井字棋与五子棋之间的处境。
     *
     * <p>只看<em>已发布版本</em>(各应用的最新版本), 不是全部历史版本: 发现链面向的是
     * 调用方"现在能做什么", 把 {@code 0.9.0} 的候选也端上去只会制造无解的歧义。
     * 历史版本仍可用 {@link #find(String, String)} 精确寻址。
     */
    public List<ApplicationManifest> byActionId(String actionId) {
        if (actionId == null) return List.of();
        return applications().stream()
                .filter(m -> m.action(actionId).isPresent())
                .toList();
    }

    /** 提供该能力的应用(只取各应用的最新版本)。 */
    public List<ApplicationManifest> byCapability(String capabilityId) {
        if (capabilityId == null) return List.of();
        return applications().stream()
                .filter(m -> m.declaresCapability(capabilityId))
                .toList();
    }

    /** 能力目录: capabilityId → 声明(取首次出现的那份, 标题以最先注册的应用为准)。 */
    public Map<String, ApplicationManifest.CapabilityDecl> capabilityCatalogue() {
        Map<String, ApplicationManifest.CapabilityDecl> out = new LinkedHashMap<>();
        for (ApplicationManifest m : applications()) {
            for (ApplicationManifest.CapabilityDecl c : m.capabilities()) {
                out.putIfAbsent(c.id(), c);
            }
        }
        return out;
    }

    /** 仅供测试清理。 */
    public void clear() {
        byAppVersion.clear();
        latestVersionByApp.clear();
    }

    private static String key(String appId, String version) {
        return appId + "@" + version;
    }

    /** 点分数字版本比较; 段数不同时短的在前。非数字段退化为字符串比较。 */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            if (i >= x.length) return -1;
            if (i >= y.length) return 1;
            int cmp = compareSegment(x[i], y[i]);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static int compareSegment(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
