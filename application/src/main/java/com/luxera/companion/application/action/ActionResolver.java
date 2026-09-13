package com.luxera.companion.application.action;

import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.manifest.UriTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1: 从 {@code (actionId, target)} 解出"这是哪个应用的哪个动作"。
 *
 * <p>消歧规则只有一条, 但它是必须的: {@code game.make_move} 同时属于井字棋和五子棋, 光看动作
 * id 是选不出来的。棋盘 URI 能 —— {@code game://session/1} 只匹配井字棋的
 * {@code game://session/{sessionId}}。所以:
 *
 * <ol>
 *   <li>候选只剩一个 → 就是它, target 只用于定位资源;</li>
 *   <li>候选多个, 且恰好一个的某个资源模板匹配 target → 就是它;</li>
 *   <li>候选多个且匹配不出唯一一个 → {@link AmbiguousActionException}, 让调用方补 target。</li>
 * </ol>
 *
 * <p>不做"取第一个候选"的兜底。那会让加第二个游戏的当天, 井字棋的请求开始偶尔落到五子棋上,
 * 而且只在两个应用都被安装时才复现。
 */
@Component
public class ActionResolver {

    private final ManifestRegistry manifests;

    public ActionResolver(ManifestRegistry manifests) {
        this.manifests = manifests;
    }

    public Optional<ActionResolution> resolve(String actionId, String target) {
        List<ApplicationManifest> candidates = manifests.byActionId(actionId);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return candidates.get(0).action(actionId).map(spec -> new ActionResolution(candidates.get(0), spec));
        }
        List<ApplicationManifest> matched = candidates.stream().filter(m -> owns(m, target)).toList();
        if (matched.size() == 1) {
            ApplicationManifest manifest = matched.get(0);
            return manifest.action(actionId).map(spec -> new ActionResolution(manifest, spec));
        }
        throw new AmbiguousActionException(actionId,
                candidates.stream().map(ApplicationManifest::applicationId).toList());
    }

    /** 这个 URI 归哪个应用管 —— 走每个应用自己声明的资源模板。 */
    public Optional<ApplicationManifest> ownerOf(String uri) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        List<ApplicationManifest> matched = manifests.applications().stream()
                .filter(m -> owns(m, uri))
                .toList();
        return matched.size() == 1 ? Optional.of(matched.get(0)) : Optional.empty();
    }

    /** 资源模板里叫 {@code sessionId} 的那一段 —— 平台约定的会话锚点。 */
    public Optional<String> sessionIdIn(ApplicationManifest manifest, String uri) {
        for (ApplicationManifest.ResourceDecl decl : manifest.resources()) {
            Optional<String> value = UriTemplate.variable(decl.uriTemplate(), uri, SESSION_VARIABLE);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static boolean owns(ApplicationManifest manifest, String uri) {
        return manifest.resources().stream()
                .anyMatch(r -> UriTemplate.matches(r.uriTemplate(), uri));
    }

    /**
     * 资源 URI 里代表 {@code ApplicationSession} 的那一段的名字。
     *
     * <p>是一条<em>平台约定</em>, 不是巧合: {@code game://session/{sessionId}} 里的
     * {@code sessionId} 就是平台分配的会话 id。约定成文字是为了让"这个 URI 属于哪个会话"
     * 不需要应用再声明一遍, 也让资源归属链在 URI 上就看得见。
     */
    public static final String SESSION_VARIABLE = "sessionId";
}
