package com.luxera.companion.application.manifest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LAP v1 §Manifest validation: 形状之外的<em>业务规则</em>。每一条都对应一次真实会踩的坑。
 *
 * <p>规则清单(错误码即定位):
 * <ul>
 *   <li>{@code MANIFEST_INVALID} —— identity/version/name 缺失, 或 capabilities/actions 为空。
 *       一个没有任何动作的"应用"在平台里毫无意义, 与其让它出现在发现链里, 不如发布失败。</li>
 *   <li>{@code INVALID_APPLICATION_ID} —— id 必须是反向域名形态(至少一个点)。</li>
 *   <li>{@code DUPLICATE_CAPABILITY} / {@code DUPLICATE_ACTION} —— 同名不是"后者覆盖前者",
 *       是作者写错了。</li>
 *   <li>{@code UNKNOWN_CAPABILITY} —— action 声明了一个没在 capabilities 里出现的能力。</li>
 *   <li>{@code PERMISSION_DECL_MISSING} / {@code PERMISSION_DECL_UNKNOWN_CAPABILITY} ——
 *       每个能力恰好一条权限声明。缺了就是"默认放行"的另一种说法, 不能接受。</li>
 *   <li>{@code INPUT_SCHEMA_REQUIRED} —— WRITE/EXECUTE 必须给 inputSchema。没有 schema 的写操作
 *       既没法给 LLM 约束, 也没法在网关入口做校验。</li>
 *   <li>{@code EVENT_ID_TEMPLATE_REQUIRED} —— {@code triggersAgent} 为真却没有 idTemplate。
 *       随机 id 会让数字人侧去重失效, 于是同一步行动被响应两次。</li>
 *   <li>{@code RESOURCE_URI_TEMPLATE_REQUIRED} —— resource 没有 uriTemplate, 网关就没法判断
 *       "这个 target 属于哪个应用"。</li>
 *   <li>{@code RUNTIME_TYPE_NOT_SUPPORTED} —— HOSTED 在本阶段明确不做, 显式报错而不是静默接受。</li>
 *   <li>{@code REMOTE_ENDPOINT_REQUIRED} —— REMOTE 却没有 {@code runtime.remote.baseUrl}。</li>
 *   <li>{@code UI_TYPE_REQUIRED} / {@code UI_ENTRY_REQUIRED} —— 声明了 {@code ui} 却没说清
 *       "谁渲染"或"从哪儿进"。</li>
 *   <li>{@code REMOTE_UI_ENTRY_NOT_ABSOLUTE} —— {@code ui.type=REMOTE} 的 entry 不是绝对
 *       http(s) 地址。这是最容易在本地看不出问题的一条: {@code /embed} 这样的相对路径会被
 *       iframe 解释成"平台自己的某个页面", 于是第三方应用"打开是平台首页", 而两边都不报错。</li>
 *   <li>{@code UI_SURFACE_MODE_CONFLICT} —— NATIVE 应用却声明了 web surface。</li>
 *   <li>{@code DUPLICATE_SURFACE} —— 同一种容器声明了两遍(两个 FULL_PAGE 入口, 客户端该用哪个)。</li>
 *   <li>{@code SURFACE_TYPE_REQUIRED} / {@code SURFACE_ENTRY_REQUIRED} —— surface 缺 type / entry。</li>
 *   <li>{@code INVALID_CLIENT_VERSION} —— {@code minClientVersion} 不像版本号。</li>
 * </ul>
 */
@Component
public class ManifestValidator {

    public void validate(ApplicationManifest manifest) {
        if (manifest == null) {
            throw ManifestException.of("MANIFEST_INVALID", "manifest 为空");
        }
        ApplicationManifest.Identity identity = manifest.identity();
        if (identity == null) {
            throw ManifestException.of("MANIFEST_INVALID", "缺少 identity section");
        }
        require(identity.id(), "MANIFEST_INVALID", "identity.id 必填");
        require(identity.name(), "MANIFEST_INVALID", "identity.name 必填");
        require(identity.version(), "MANIFEST_INVALID", "identity.version 必填");
        if (!identity.id().contains(".")) {
            throw ManifestException.of("INVALID_APPLICATION_ID",
                    "identity.id 必须是反向域名形态(如 com.luxera.tictactoe), 实际: " + identity.id());
        }
        if (manifest.capabilities().isEmpty()) {
            throw ManifestException.of("MANIFEST_INVALID", "capabilities 不能为空 —— 发现链的第一级就是能力");
        }
        if (manifest.actions().isEmpty()) {
            throw ManifestException.of("MANIFEST_INVALID", "actions 不能为空 —— 没有动作的应用无法被发现");
        }

        validateCapabilities(manifest);
        validateActions(manifest);
        validateResources(manifest);
        validateEvents(manifest);
        validatePermissions(manifest);
        validateRuntime(manifest);
        validateUi(manifest);
    }

    /**
     * 第八段: {@code ui}。<b>它整段可以不写</b> —— 缺席表示"平台默认的全页内嵌应用"。
     *
     * <p>写了就必须写全: 说得出谁渲染({@code type})、从哪儿进({@code entry})。半写的 {@code ui}
     * 比不写更坏 —— 客户端拿到一个没有入口的声明, 只能各自发明一个兜底值, 于是同一个应用在
     * 网页端和在聊天里从两个不同的地方进去。
     */
    private void validateUi(ApplicationManifest m) {
        ApplicationManifest.UiDecl ui = m.ui();
        if (ui == null) {
            return;   // 缺席是合法的, 见方法注释
        }
        if (ui.type() == null) {
            throw ManifestException.of("UI_TYPE_REQUIRED",
                    "声明了 ui 就必须说清 type (EMBEDDED / REMOTE / NATIVE)");
        }
        // NATIVE 的界面在客户端里, 平台没有可打开的入口 —— 要求 entry 反而会逼作者编一个假的。
        if (ui.type() != ApplicationManifest.UiMode.NATIVE) {
            require(ui.entry(), "UI_ENTRY_REQUIRED",
                    "ui.type=" + ui.type() + " 必须给 entry —— 客户端要有一个进去的地方");
        }
        if (ui.type() == ApplicationManifest.UiMode.REMOTE && !isAbsoluteHttpUrl(ui.entry())) {
            throw ManifestException.of("REMOTE_UI_ENTRY_NOT_ABSOLUTE",
                    "ui.type=REMOTE 的 entry 必须是绝对 http(s) 地址, 实际: " + ui.entry()
                            + " —— 相对路径会被 iframe 当成平台自己的页面");
        }
        if (ui.minClientVersion() != null && !ui.minClientVersion().matches("\\d+(\\.\\d+)*")) {
            throw ManifestException.of("INVALID_CLIENT_VERSION",
                    "minClientVersion 必须是点分数字(如 1.0.0), 实际: " + ui.minClientVersion());
        }
        if (ui.type() == ApplicationManifest.UiMode.NATIVE && !ui.surfaces().isEmpty()) {
            throw ManifestException.of("UI_SURFACE_MODE_CONFLICT",
                    "ui.type=NATIVE 的应用不该声明 web surface —— 它的界面不在网页里");
        }

        Set<ApplicationManifest.SurfaceType> seen = new HashSet<>();
        for (ApplicationManifest.SurfaceDecl s : ui.surfaces()) {
            if (s.type() == null) {
                throw ManifestException.of("SURFACE_TYPE_REQUIRED",
                        "surface 必须给 type, 合法值: " + java.util.Arrays
                                .toString(ApplicationManifest.SurfaceType.values()));
            }
            if (!seen.add(s.type())) {
                throw ManifestException.of("DUPLICATE_SURFACE",
                        "同一种 surface 声明了两遍: " + s.type()
                                + " —— 两个入口等于没有入口, 客户端不知道该用哪个");
            }
            require(s.entry(), "SURFACE_ENTRY_REQUIRED",
                    "surface " + s.type() + " 必须给 entry");
        }
    }

    private static boolean isAbsoluteHttpUrl(String value) {
        return value != null
                && (value.startsWith("http://") || value.startsWith("https://"))
                && value.length() > "https://".length();
    }

    private void validateCapabilities(ApplicationManifest m) {
        Set<String> seen = new HashSet<>();
        for (ApplicationManifest.CapabilityDecl c : m.capabilities()) {
            require(c.id(), "MANIFEST_INVALID", "capability.id 必填");
            if (!seen.add(c.id())) {
                throw ManifestException.of("DUPLICATE_CAPABILITY", "能力重复声明: " + c.id());
            }
        }
    }

    private void validateActions(ApplicationManifest m) {
        Set<String> seen = new HashSet<>();
        for (ApplicationManifest.ActionDecl a : m.actions()) {
            require(a.id(), "MANIFEST_INVALID", "action.id 必填");
            if (!seen.add(a.id())) {
                throw ManifestException.of("DUPLICATE_ACTION", "动作重复声明: " + a.id());
            }
            require(a.capability(), "MANIFEST_INVALID", "动作 " + a.id() + " 缺少 capability");
            if (!m.declaresCapability(a.capability())) {
                throw ManifestException.of("UNKNOWN_CAPABILITY",
                        "动作 " + a.id() + " 声明了未定义的能力 " + a.capability());
            }
            if (a.permission() == null) {
                throw ManifestException.of("MANIFEST_INVALID", "动作 " + a.id() + " 缺少 permission");
            }
            if (a.risk() == null) {
                throw ManifestException.of("MANIFEST_INVALID", "动作 " + a.id() + " 缺少 risk");
            }
            if (a.requiresIdempotencyKey()
                    && (a.inputSchema() == null || !a.inputSchema().isObject())) {
                throw ManifestException.of("INPUT_SCHEMA_REQUIRED",
                        a.permission() + " 动作 " + a.id() + " 必须声明对象型 inputSchema");
            }
        }
    }

    private void validateResources(ApplicationManifest m) {
        for (ApplicationManifest.ResourceDecl r : m.resources()) {
            require(r.type(), "MANIFEST_INVALID", "resource.type 必填");
            require(r.uriTemplate(), "RESOURCE_URI_TEMPLATE_REQUIRED",
                    "资源 " + r.type() + " 必须声明 uriTemplate —— 网关靠它判断 target 属于哪个应用");
        }
    }

    private void validateEvents(ApplicationManifest m) {
        Set<String> seen = new HashSet<>();
        for (ApplicationManifest.EventDecl e : m.events()) {
            require(e.type(), "MANIFEST_INVALID", "event.type 必填");
            if (!seen.add(e.type())) {
                throw ManifestException.of("DUPLICATE_EVENT", "事件类型重复声明: " + e.type());
            }
            if (e.triggersAgent() && isBlank(e.idTemplate())) {
                throw ManifestException.of("EVENT_ID_TEMPLATE_REQUIRED",
                        "事件 " + e.type() + " 会唤起数字人, 必须声明 idTemplate —— "
                                + "随机 id 会让数字人侧的去重失效, 同一步行动被响应两次");
            }
        }
    }

    private void validatePermissions(ApplicationManifest m) {
        List<String> declared = new ArrayList<>();
        for (ApplicationManifest.PermissionDecl p : m.permissions()) {
            require(p.capability(), "MANIFEST_INVALID", "permission.capability 必填");
            if (p.level() == null) {
                throw ManifestException.of("MANIFEST_INVALID", "权限声明 " + p.capability() + " 缺少 level");
            }
            if (p.riskCeiling() == null) {
                throw ManifestException.of("MANIFEST_INVALID",
                        "权限声明 " + p.capability() + " 缺少 riskCeiling");
            }
            if (!m.declaresCapability(p.capability())) {
                throw ManifestException.of("PERMISSION_DECL_UNKNOWN_CAPABILITY",
                        "权限声明指向未定义的能力 " + p.capability());
            }
            declared.add(p.capability());
        }
        for (ApplicationManifest.CapabilityDecl c : m.capabilities()) {
            if (!declared.contains(c.id())) {
                throw ManifestException.of("PERMISSION_DECL_MISSING",
                        "能力 " + c.id() + " 没有权限声明 —— 缺声明不等于默认放行");
            }
        }
    }

    private void validateRuntime(ApplicationManifest m) {
        ApplicationManifest.RuntimeDecl runtime = m.runtime();
        if (runtime == null || runtime.type() == null) {
            throw ManifestException.of("MANIFEST_INVALID", "缺少 runtime.type");
        }
        switch (runtime.type()) {
            case HOSTED -> throw ManifestException.of("RUNTIME_TYPE_NOT_SUPPORTED",
                    "runtime.type=HOSTED 在本阶段不支持 —— 平台不替应用跑代码(JVM sandbox / WASM 已明确推迟)");
            case REMOTE -> {
                if (runtime.remote() == null || isBlank(runtime.remote().baseUrl())) {
                    throw ManifestException.of("REMOTE_ENDPOINT_REQUIRED",
                            "runtime.type=REMOTE 必须声明 runtime.remote.baseUrl");
                }
            }
            case NATIVE -> { /* 进程内, 无需额外声明 */ }
        }
    }

    private static void require(String value, String code, String message) {
        if (isBlank(value)) throw ManifestException.of(code, message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
