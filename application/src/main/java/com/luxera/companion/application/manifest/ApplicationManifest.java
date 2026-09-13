package com.luxera.companion.application.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1 §Manifest: 一份应用的完整声明。**它是 LAP 的中枢** —— 平台靠它发现应用、
 * 校验动作、生成 LLM 上下文, 应用靠它把"我是什么、我能做什么、怎么读我"一次说清。
 *
 * <p><b>Manifest 属于 {@code ApplicationVersion}, 不属于 Application。</b> 于是
 * "同一个应用的两个版本"在数据模型里表达得出来, 发布后冻结的是<em>那一版</em>的 manifest,
 * 而 {@code action_invocation} 指向的历史版本永远可解释。
 *
 * <p><b>恰好八个 section:</b> {@code identity} / {@code capabilities} / {@code actions} /
 * {@code resources} / {@code events} / {@code permissions} / {@code runtime} / {@code ui}。
 * 解析器对未知 section 直接报错 —— 多一个 section 就意味着多一套平台还不认识的语义,
 * 静默忽略它比报错危险得多。
 *
 * <p><b>这里绝不出现按消费者分的 endpoint</b>({@code agentEndpoint} / {@code humanEndpoint} /
 * {@code mcpEndpoint})。真人和 Agent 走的是<em>同一条</em> {@code actions:execute};
 * REMOTE 应用有且只有一个规范 endpoint, 落在 {@code runtime.remote}。
 *
 * <p><b>{@code ui} 是第八个 section, 也是唯一一个"只描述呈现、不描述行为"的 section。</b>
 * 它只说三件事: {@code surface type} / {@code entry} / {@code minimum client version}(§68),
 * 因为 LAP 是 Application Runtime Protocol, 不是 UI Rendering Protocol(§69)。
 */
public record ApplicationManifest(
        Identity identity,
        List<CapabilityDecl> capabilities,
        List<ActionDecl> actions,
        List<ResourceDecl> resources,
        List<EventDecl> events,
        List<PermissionDecl> permissions,
        RuntimeDecl runtime,
        UiDecl ui
) {

    /** 解析器保证的八个合法 section 名。 */
    public static final List<String> SECTIONS = List.of(
            "identity", "capabilities", "actions", "resources", "events", "permissions",
            "runtime", "ui");

    public ApplicationManifest {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        actions = actions == null ? List.of() : List.copyOf(actions);
        resources = resources == null ? List.of() : List.copyOf(resources);
        events = events == null ? List.of() : List.copyOf(events);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    /**
     * 八个 section 里唯一可以缺席的一个。
     *
     * <p>缺席 ≠ 没有 UI, 而是"<em>用平台默认的那种</em>"—— 一个由平台自己渲染的全页应用。
     * 把默认值留在这里而不是在解析器里塞一个假的 {@code UiDecl}, 是因为"作者声明了什么"与
     * "<em>客户端该拿到什么</em>"是两件事: 前者是 manifest, 后者是投影(见 {@code SurfaceCatalogue})。
     * 一条提醒数据没有自己的界面, 但它仍然可以在平台里被打开。
     */
    public Optional<UiDecl> uiDeclaration() {
        return Optional.ofNullable(ui);
    }

    public String applicationId() {
        return identity == null ? null : identity.id();
    }

    public String version() {
        return identity == null ? null : identity.version();
    }

    public Optional<ActionDecl> action(String actionId) {
        return actions.stream().filter(a -> a.id().equals(actionId)).findFirst();
    }

    public Optional<ResourceDecl> resourceOfType(String resourceType) {
        return resources.stream().filter(r -> r.type().equals(resourceType)).findFirst();
    }

    /** 该动作所属能力是否为 manifest 声明的能力之一。 */
    public boolean declaresCapability(String capabilityId) {
        return capabilities.stream().anyMatch(c -> c.id().equals(capabilityId));
    }

    // ─────────────────────────── 七个 section ───────────────────────────

    /** {@code identity} —— 应用是谁。id 是稳定标识, version 让 manifest 与版本绑定。 */
    public record Identity(String id, String name, String version, String description, String category) {}

    /** {@code capabilities} —— 粗粒度能力({@code game.play})。发现链的第一级。 */
    public record CapabilityDecl(String id, String title, String description, String category) {}

    /**
     * {@code actions} —— 细粒度动作({@code game.make_move})。
     *
     * <p>权限/风险/注意力都挂在动作上: 能力是粗的(给人挑), 动作是细的(给权限和 LLM 用)。
     * {@code agentHint} 是应用作者写给 LLM 的"怎么做", 它取代了原先硬编码在
     * {@code AgentRuntime} 里的走子策略 —— 加第二个游戏不必再改数字人。
     */
    public record ActionDecl(
            String id,
            String capability,
            String title,
            String description,
            PermissionLevel permission,
            RiskLevel risk,
            AttentionPolicy attention,
            JsonNode inputSchema,
            String agentHint
    ) {
        /** WRITE / EXECUTE 必须幂等键; READ 从不记录 invocation。 */
        public boolean requiresIdempotencyKey() {
            return permission == PermissionLevel.WRITE || permission == PermissionLevel.EXECUTE;
        }

        public boolean isRead() {
            return permission == PermissionLevel.READ;
        }
    }

    /**
     * {@code resources} —— 统一读模型。{@code uriTemplate} 决定什么样的 target 属于本应用。
     *
     * <p>{@code backing} 决定状态存在哪: {@link Backing#RESOURCE_STORE} 就是那条 resource
     * 记录本身(棋局); {@link Backing#APP_OWNED} 说明应用自己有真表, 读的时候由
     * {@code ResourceProjector} 投影出来(提醒需要 {@code WHERE due_at <= now()} 这类索引查询,
     * JSON 列做不到)。两种都在这儿声明, 读接口对调用方完全一样。
     */
    public record ResourceDecl(String type, String uriTemplate, Backing backing, String agentHint) {}

    public enum Backing {
        /** 状态就是 {@code resource.state_json}。 */
        RESOURCE_STORE,
        /** 状态在应用自己的表里, 读时投影。 */
        APP_OWNED
    }

    /**
     * {@code events} —— 应用会发出什么事件。
     *
     * <p><b>两级闸门的第一级在类型上</b>: {@code triggersAgent} 说"这类事件<em>可以</em>
     * 唤起数字人", 实例级的 {@code data.agentTrigger} 才说"<em>这一次</em>该不该"。
     * 取与。
     *
     * <p>{@code triggersAgent} 为真时 {@code idTemplate} <b>强制必填</b>: 事件 id 必须是
     * (资源, 类型, 关键参数) 的确定函数。用随机 UUID 的话, 重试会让数字人对着同一步行动两次。
     */
    public record EventDecl(String type, String title, boolean triggersAgent, String idTemplate) {}

    /**
     * {@code permissions} —— 应用声明的授权上限。真正的决策是
     * {@code Principal × Installation grant × Capability × Action × Risk}, 这里给的是
     * "本应用最多允许到什么程度"; 缺一条能力声明就直接拒绝发布, 免得出现"忘了写权限"
     * 被当成"默认放行"。
     */
    public record PermissionDecl(String capability, PermissionLevel level, RiskLevel riskCeiling) {}

    /** {@code runtime} —— 跑在哪。REMOTE 才有 {@code remote}。 */
    public record RuntimeDecl(RuntimeType type, RemoteDecl remote) {
        public static RuntimeDecl nativeRuntime() {
            return new RuntimeDecl(RuntimeType.NATIVE, null);
        }
    }

    /**
     * REMOTE 的唯一规范 endpoint。{@code authRef} 是**名字**(从平台配置里解析),
     * manifest 里永远不出现密钥 —— manifest 会进数据库、进日志、进导出包。
     */
    public record RemoteDecl(String baseUrl, String authRef) {}

    // ─────────────────────────── 第八个 section: ui ───────────────────────────

    /**
     * {@code ui} —— <b>这个应用可以被怎样呈现</b>(§17/§18/§67/§68)。
     *
     * <p>平台对 UI 只认三样东西, 多一样都不认:
     * <ul>
     *   <li>{@code type} —— {@link UiMode}。谁来渲染: 平台自己(EMBEDDED) / 应用自己的网页
     *       (REMOTE) / 原生客户端(NATIVE)。</li>
     *   <li>{@code entry} —— 从哪儿进去。<b>它是一个模板</b>, 只有 {@code {applicationId}} 与
     *       {@code {sessionId}} 两个变量; 客户端做且只做变量替换。</li>
     *   <li>{@code surfaces[]} —— 同一个应用可以被放进哪几种容器, 每种容器各自的入口。</li>
     *   <li>{@code minClientVersion} —— 低于这个版本号的客户端不该尝试渲染(它可能还不认识
     *       新的 surface type)。</li>
     * </ul>
     *
     * <p><b>刻意没有的东西</b>: 按钮、颜色、布局、字号、组件……(§69)。一旦这里出现了
     * {@code layout} 或 {@code theme}, 平台就变成在造一个 Flutter —— 而它本来只需要
     * 知道"把哪个应用放进哪个容器、从哪个入口进"。
     */
    public record UiDecl(UiMode type, String entry, String minClientVersion,
                         List<SurfaceDecl> surfaces) {

        public UiDecl {
            surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
        }
    }

    /** 谁渲染这个应用的界面。三种, 与 §18 一一对应。 */
    public enum UiMode {
        /** 平台自己渲染 —— 内置应用、官方应用、需要和平台深度互动的应用。 */
        EMBEDDED,
        /** 第三方开发者自己提供一个网页, 平台通过 iframe / WebView / 独立页面加载。 */
        REMOTE,
        /** 未来的移动端 / 桌面原生应用。平台只知道它存在, 渲染完全在客户端之外。 */
        NATIVE
    }

    /**
     * 一个 surface —— <b>同一个应用能被放进的一种容器</b>(§67)。
     *
     * <p>五种容器的区别不在"应用长什么样", 而在"它在页面上占多大、和别的东西怎么共处":
     * 整页独享 / 嵌在聊天流里 / 弹成对话框 / 挂在侧栏 / 压成一行。应用本身一行代码都不用改 ——
     * 这正是把 surface 做成 manifest 声明而不是应用代码分支的理由。
     */
    public record SurfaceDecl(SurfaceType type, String entry) {}

    /** §67 的五个类型, 一个不多一个不少。 */
    public enum SurfaceType {
        /** 整页独享 —— 应用市场点进去的默认样子。 */
        FULL_PAGE,
        /** 嵌在宿主页面里(聊天流、卡片内)。 */
        EMBEDDED,
        /** 模态对话框。 */
        MODAL,
        /** 侧栏 / 抽屉。 */
        PANEL,
        /** 压成一行或一小块的缩略呈现。 */
        INLINE
    }
}
