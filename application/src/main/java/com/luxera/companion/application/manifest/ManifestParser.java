package com.luxera.companion.application.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * JSON → {@link ApplicationManifest}。**只做形状**, 不做业务规则 ——
 * "WRITE 必须有 inputSchema""triggersAgent 必须给 idTemplate"这些属于
 * {@link ManifestValidator}, 分开放是为了让"解析不了"和"解析了但不合法"给出不同的错误码。
 *
 * <p>这里唯一一条强规则是 **section 白名单**: 顶层出现八个之外的名字直接
 * {@code UNKNOWN_SECTION}。理由见 {@link ApplicationManifest} 的类注释 ——
 * 静默忽略一个平台还不认识的 section, 等于让作者以为它生效了。
 *
 * <p><b>{@code ui} 段里还有第二道白名单</b>({@code UI_UNSUPPORTED_KEY})。它比第一道更要紧:
 * 顶层多一个 section 是作者在声明一件平台做不到的事, 而 {@code ui} 里多一个
 * {@code layout} / {@code theme} / {@code component} 是<b>平台正在变成 UI 框架</b>的开始 ——
 * 那种滑落不会有编译错误, 只会有一份越来越长的"平台认得的 UI 参数"清单(§69)。
 * 所以这里连"认不出来就忽略"都不允许: 要么平台真的认它, 要么作者把它删掉。
 */
@Component
public class ManifestParser {

    /** {@code ui} 段允许出现的键 —— 恰好是 §68 说的那几样。 */
    private static final List<String> UI_KEYS = List.of("type", "entry", "minClientVersion", "surfaces");

    /** 一个 surface 允许出现的键。 */
    private static final List<String> SURFACE_KEYS = List.of("type", "entry");

    private final ObjectMapper objectMapper;

    public ManifestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ApplicationManifest parse(String json) {
        if (json == null || json.isBlank()) {
            throw ManifestException.of("MANIFEST_EMPTY", "manifest 为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR", "manifest 不是合法 JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR", "manifest 顶层必须是对象");
        }
        rejectUnknownSections(root);
        return new ApplicationManifest(
                identity(root.path("identity")),
                capabilities(root.path("capabilities")),
                actions(root.path("actions")),
                resources(root.path("resources")),
                events(root.path("events")),
                permissions(root.path("permissions")),
                runtime(root.path("runtime")),
                ui(root.path("ui")));
    }

    private static void rejectUnknownSections(JsonNode root) {
        List<String> unknown = new ArrayList<>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!ApplicationManifest.SECTIONS.contains(name)) unknown.add(name);
        }
        if (!unknown.isEmpty()) {
            throw ManifestException.of("UNKNOWN_SECTION",
                    "manifest 含未知 section " + unknown + "; 合法 section 恰好是 " + ApplicationManifest.SECTIONS);
        }
    }

    // ─────────────────────────── 各 section ───────────────────────────

    private static ApplicationManifest.Identity identity(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        return new ApplicationManifest.Identity(
                text(n, "id"), text(n, "name"), text(n, "version"),
                text(n, "description"), text(n, "category"));
    }

    private static List<ApplicationManifest.CapabilityDecl> capabilities(JsonNode n) {
        List<ApplicationManifest.CapabilityDecl> out = new ArrayList<>();
        for (JsonNode c : array(n)) {
            out.add(new ApplicationManifest.CapabilityDecl(
                    text(c, "id"), text(c, "title"), text(c, "description"), text(c, "category")));
        }
        return out;
    }

    private static List<ApplicationManifest.ActionDecl> actions(JsonNode n) {
        List<ApplicationManifest.ActionDecl> out = new ArrayList<>();
        for (JsonNode a : array(n)) {
            out.add(new ApplicationManifest.ActionDecl(
                    text(a, "id"),
                    text(a, "capability"),
                    text(a, "title"),
                    text(a, "description"),
                    enumOf(a, "permission", PermissionLevel.class),
                    enumOf(a, "risk", RiskLevel.class),
                    enumOfDefault(a, "attention", AttentionPolicy.class, AttentionPolicy.AWARE),
                    a.hasNonNull("inputSchema") ? a.get("inputSchema") : null,
                    text(a, "agentHint")));
        }
        return out;
    }

    private static List<ApplicationManifest.ResourceDecl> resources(JsonNode n) {
        List<ApplicationManifest.ResourceDecl> out = new ArrayList<>();
        for (JsonNode r : array(n)) {
            out.add(new ApplicationManifest.ResourceDecl(
                    text(r, "type"), text(r, "uriTemplate"),
                    enumOfDefault(r, "backing", ApplicationManifest.Backing.class,
                            ApplicationManifest.Backing.RESOURCE_STORE),
                    text(r, "agentHint")));
        }
        return out;
    }

    private static List<ApplicationManifest.EventDecl> events(JsonNode n) {
        List<ApplicationManifest.EventDecl> out = new ArrayList<>();
        for (JsonNode e : array(n)) {
            out.add(new ApplicationManifest.EventDecl(
                    text(e, "type"), text(e, "title"),
                    e.path("triggersAgent").asBoolean(false),
                    text(e, "idTemplate")));
        }
        return out;
    }

    private static List<ApplicationManifest.PermissionDecl> permissions(JsonNode n) {
        List<ApplicationManifest.PermissionDecl> out = new ArrayList<>();
        for (JsonNode p : array(n)) {
            out.add(new ApplicationManifest.PermissionDecl(
                    text(p, "capability"),
                    enumOf(p, "level", PermissionLevel.class),
                    enumOf(p, "riskCeiling", RiskLevel.class)));
        }
        return out;
    }

    private static ApplicationManifest.RuntimeDecl runtime(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;   // validator 会拒绝(runtime 必填)
        RuntimeType type = enumOf(n, "type", RuntimeType.class);
        JsonNode remote = n.path("remote");
        ApplicationManifest.RemoteDecl remoteDecl = (remote.isMissingNode() || remote.isNull())
                ? null
                : new ApplicationManifest.RemoteDecl(text(remote, "baseUrl"), text(remote, "authRef"));
        return new ApplicationManifest.RuntimeDecl(type, remoteDecl);
    }

    /**
     * {@code ui} —— 八段里唯一可以整段缺席的一段, 缺席即"平台默认的全页内嵌应用"。
     *
     * <p>缺席时不在这里补一个默认的 {@code UiDecl}: 补在这里就等于把"作者没写"和"作者写了
     * 平台默认值"记成同一件事, 而后者在版本对比时会看不见变化。默认值属于投影层。
     */
    private static ApplicationManifest.UiDecl ui(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        if (!n.isObject()) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR", "ui 必须是对象, 实际是 " + n.getNodeType());
        }
        rejectUnsupportedKeys(n, UI_KEYS, "ui", "UI_UNSUPPORTED_KEY",
                "ui 段只认 surface type / entry / minimum client version(§69)");

        List<ApplicationManifest.SurfaceDecl> surfaces = new ArrayList<>();
        for (JsonNode s : array(n.path("surfaces"))) {
            if (!s.isObject()) {
                throw ManifestException.of("MANIFEST_PARSE_ERROR",
                        "ui.surfaces 的每一项必须是对象, 实际是 " + s.getNodeType());
            }
            rejectUnsupportedKeys(s, SURFACE_KEYS, "ui.surfaces[]", "UI_UNSUPPORTED_KEY",
                    "一个 surface 只有 type 与 entry 两件事");
            surfaces.add(new ApplicationManifest.SurfaceDecl(
                    enumOf(s, "type", ApplicationManifest.SurfaceType.class), text(s, "entry")));
        }
        return new ApplicationManifest.UiDecl(
                enumOf(n, "type", ApplicationManifest.UiMode.class),
                text(n, "entry"),
                text(n, "minClientVersion"),
                surfaces);
    }

    /**
     * 段内键白名单。与 {@link #rejectUnknownSections} 同一理由, 只是下沉了一层:
     * 未知的<em>顶层 section</em> 是"作者以为平台有这功能", 未知的 <em>ui 键</em> 是
     * "作者以为平台该渲染这个东西" —— 后者正是 §69 要挡的那条路。
     */
    private static void rejectUnsupportedKeys(JsonNode n, List<String> allowed, String where,
                                              String code, String why) {
        List<String> unknown = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!allowed.contains(name)) unknown.add(name);
        }
        if (!unknown.isEmpty()) {
            throw ManifestException.of(code,
                    where + " 含不认识的键 " + unknown + "; " + why + "。合法键恰好是 " + allowed);
        }
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private static Iterable<JsonNode> array(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return List.of();
        if (!n.isArray()) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR", "期望数组, 实际是 " + n.getNodeType());
        }
        return n;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }

    private static <E extends Enum<E>> E enumOf(JsonNode n, String field, Class<E> type) {
        String raw = text(n, field);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR",
                    field + "=" + raw + " 不是合法的 " + type.getSimpleName());
        }
    }

    private static <E extends Enum<E>> E enumOfDefault(JsonNode n, String field, Class<E> type, E fallback) {
        E v = enumOf(n, field, type);
        return v == null ? fallback : v;
    }
}
