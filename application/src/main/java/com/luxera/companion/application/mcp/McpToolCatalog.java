package com.luxera.companion.application.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.lifecycle.ApplicationCatalogue;
import com.luxera.companion.application.manifest.ApplicationManifest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LAP v1 §MCP: <b>动作 → MCP 工具</b>的那一层翻译。
 *
 * <p>翻译的方向是刻意单向的: <em>manifest 是真相, 工具描述是它的投影</em>。这里不新增任何
 * 语义、不缓存任何东西 —— 每次 {@code tools/list} 都由 {@link ApplicationCatalogue} 现算一遍。
 * 缓存一份工具表意味着"应用发了新版本但工具列表还是旧的", 而这类不一致没有第二个地方能发现。
 *
 * <p><b>工具名 = {@code <应用短名>.<动作 id 里的点换成下划线>}</b>, 例如
 * {@code tictactoe.game_make_move}。动作 id 保留着点就没法当工具名用(多数客户端按
 * {@code [A-Za-z0-9_-]} 校验名字), 所以只把动作 id 里的点和工具名分隔点区分开。
 *
 * <p><b>短名撞车时自动退化成全名。</b>井字棋与五子棋都声明 {@code game.make_move}, 但那是
 * <em>动作 id</em> 撞车, 平台靠资源 URI 消歧; 工具名这一层是另一回事: 若有两个应用叫
 * {@code a.b} 和 {@code c.b}, 短名都是 {@code b}, 工具名就会撞。撞了就都改用全名
 * ({@code com_luxera_a_b.game_make_move}), <em>整个目录一起</em>改 —— 只给其中一个改的话,
 * 工具名会变成"取决于另一个应用存不存在"的东西。
 */
@Component
public class McpToolCatalog {

    /** {@code arguments} 里被平台占用的名字, 不进动作的 {@code input}。 */
    public static final String ARG_TARGET = "target";
    public static final String ARG_EXPECTED_VERSION = "expectedResourceVersion";
    public static final String ARG_IDEMPOTENCY_KEY = "_idempotencyKey";

    private static final List<String> RESERVED_ARGUMENTS =
            List.of(ARG_TARGET, ARG_EXPECTED_VERSION, ARG_IDEMPOTENCY_KEY);

    private final ApplicationCatalogue catalogue;
    private final ObjectMapper objectMapper;

    public McpToolCatalog(ApplicationCatalogue catalogue, ObjectMapper objectMapper) {
        this.catalogue = catalogue;
        this.objectMapper = objectMapper;
    }

    /** 一个工具就是"(某个应用的)某个动作" —— 没有第三样东西。 */
    public record McpTool(String name,
                          String applicationId,
                          String actionId,
                          ApplicationManifest manifest,
                          ApplicationManifest.ActionDecl action) {

        public boolean isRead() {
            return action.isRead();
        }
    }

    // ─────────────────────────── 目录 ───────────────────────────

    /**
     * 当前可见的全部工具, 可按能力或应用收窄。
     *
     * <p><b>不按"装没装"过滤。</b>发现不是授权: 一个还没安装的应用当然要能被列出来, 否则
     * 调用方永远不知道有它可装。装没装由 {@code ActionGateway} 在第 5 步判, 那里答得比这里准
     * (它知道这个 principal 的哪条授权)。
     *
     * <p>收窄参数是 LAP 加的({@code tools/list} 规范里只有 {@code cursor}): 这正是
     * "不要把 50000 个 action 塞给 LLM"在传输层的落点 —— Agent 先选能力, 再选应用, 再拿动作。
     */
    public List<McpTool> tools(String capabilityId, String applicationId) {
        // 发现链与 REST 面共用同一份"谁在架"的判断 —— 下架的应用不该在这个传输面上还活着。
        List<ApplicationManifest> all = catalogue.discoverable();
        Map<String, Integer> shortNameCensus = census(all);

        Map<String, McpTool> byName = new LinkedHashMap<>();
        for (ApplicationManifest manifest : all) {
            if (StringUtils.hasText(applicationId) && !applicationId.equals(manifest.applicationId())) {
                continue;
            }
            if (StringUtils.hasText(capabilityId) && !manifest.declaresCapability(capabilityId)) {
                continue;
            }
            boolean qualified = shortNameCensus.getOrDefault(shortName(manifest.applicationId()), 0) > 1;
            for (ApplicationManifest.ActionDecl action : manifest.actions()) {
                String name = toolName(manifest.applicationId(), action.id(), qualified);
                McpTool tool = new McpTool(name, manifest.applicationId(), action.id(), manifest, action);
                McpTool clash = byName.putIfAbsent(name, tool);
                if (clash != null) {
                    // 连全名都撞 → 两个应用 id 只差标点(com.luxera.a_b 与 com.luxera.a.b)。
                    // 静默丢掉一个会让某个应用永远调不到, 而且没人会知道 —— 只能报错。
                    throw new IllegalStateException("MCP 工具名撞车: " + name + " 同时属于 "
                            + clash.applicationId() + " 与 " + tool.applicationId());
                }
            }
        }
        return List.copyOf(byName.values());
    }

    public Optional<McpTool> find(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return Optional.empty();
        }
        return tools(null, null).stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst();
    }

    /** 工具名 → 动作 id 里被换掉的那些字符, 只在这里出现一次。 */
    static String toolName(String applicationId, String actionId, boolean qualified) {
        String appPart = qualified
                ? applicationId.replace('.', '_')
                : shortName(applicationId);
        return appPart + "." + actionId.replace('.', '_');
    }

    static String shortName(String applicationId) {
        if (applicationId == null) {
            return "";
        }
        int dot = applicationId.lastIndexOf('.');
        return dot < 0 ? applicationId : applicationId.substring(dot + 1);
    }

    private static Map<String, Integer> census(List<ApplicationManifest> all) {
        Map<String, Integer> census = new LinkedHashMap<>();
        for (ApplicationManifest manifest : all) {
            census.merge(shortName(manifest.applicationId()), 1, Integer::sum);
        }
        return census;
    }

    // ─────────────────────────── 描述 ───────────────────────────

    /**
     * 一个工具的 {@code tools/list} 形状。
     *
     * <p>{@code description} 是这个类的全部价值所在: 调用方(通常是 LLM)对应用的了解<em>只有</em>
     * 这一段文字。所以它必须带上四样东西 —— 应用名、这个动作干什么、target 长什么样、
     * 以及作者写的 {@code agentHint}(策略提示就住在那儿, 不在数字人的代码里)。
     */
    public ObjectNode describe(McpTool tool) {
        ApplicationManifest.ActionDecl action = tool.action();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", tool.name());
        if (StringUtils.hasText(action.title())) {
            node.put("title", action.title());
        }
        node.put("description", describeText(tool));
        node.set("inputSchema", inputSchemaOf(tool));

        // 给客户端的提示, 不是安全边界 —— 真正的判定在 PermissionEvaluator。
        ObjectNode annotations = node.putObject("annotations");
        annotations.put("readOnlyHint", action.isRead());
        annotations.put("destructiveHint", false);
        annotations.put("openWorldHint", false);
        // LAP 里每个动作都是幂等的: READ 天然是; WRITE/EXECUTE 由必需的 Idempotency-Key 保证。
        // 所以这一条恒为真 —— 它是平台给调用方的承诺, 不是从动作上推出来的性质。
        annotations.put("idempotentHint", true);
        return node;
    }

    /**
     * 描述文字。与上面那个 {@code describe} 分开命名, 不是洁癖 —— 两个方法同名同参、
     * 只有返回类型不同, 在 Java 里根本不是重载, 是重定义。
     */
    private String describeText(McpTool tool) {
        ApplicationManifest.ActionDecl action = tool.action();
        StringBuilder out = new StringBuilder();
        out.append("【").append(tool.manifest().identity().name())
                .append(" · ").append(action.title() == null ? tool.actionId() : action.title())
                .append("】");
        if (StringUtils.hasText(action.description())) {
            out.append(action.description());
        }
        out.append("\n").append(ARG_TARGET).append(": 要操作哪个资源, 形如 ")
                .append(targetHint(tool.manifest()));
        for (ApplicationManifest.ResourceDecl resource : tool.manifest().resources()) {
            if (StringUtils.hasText(resource.agentHint())) {
                out.append("\n资源说明(").append(resource.type()).append("): ").append(resource.agentHint());
            }
        }
        if (StringUtils.hasText(action.agentHint())) {
            out.append("\n提示: ").append(action.agentHint());
        }
        return out.toString();
    }

    private static String targetHint(ApplicationManifest manifest) {
        if (manifest.resources().isEmpty()) {
            return "(本应用没有声明资源)";
        }
        return manifest.resources().stream()
                .map(ApplicationManifest.ResourceDecl::uriTemplate)
                .reduce((a, b) -> a + " / " + b)
                .orElse("");
    }

    /**
     * 工具的参数表 = 动作自己的 {@code inputSchema} <b>+ 一个平台级的 {@code target}</b>。
     *
     * <p>刻意<b>不</b>套一层 {@code {"input": {...}}}: MCP 客户端是照着这份 schema 填参数的,
     * 声明成嵌套就得多填一层, 而多出来的一层除了复述 LAP 的内部结构之外没有任何用处。
     * 收进 {@code ActionRequest.input} 是传输层的事(见 {@link McpController})。
     *
     * <p>{@code expectedResourceVersion} 与 {@code _idempotencyKey} <b>能收但不宣传</b>:
     * 前者要有"当前版本"才有意义, 而 MCP 面上暂时没有读资源的工具; 后者本来就是给那些没法
     * 设请求头的客户端准备的退路。把它们写进 schema, 只会邀请模型为一个它无从知道的字段编个值。
     */
    private ObjectNode inputSchemaOf(McpTool tool) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");

        ObjectNode target = properties.putObject(ARG_TARGET);
        target.put("type", "string");
        target.put("description", "要操作哪个资源, 形如 " + targetHint(tool.manifest()));
        required.add(ARG_TARGET);

        JsonNode declared = tool.action().inputSchema();
        if (declared != null && declared.isObject()) {
            JsonNode declaredProperties = declared.get("properties");
            if (declaredProperties != null && declaredProperties.isObject()) {
                declaredProperties.fields().forEachRemaining(entry -> {
                    // target 是平台保留名, 动作自己的同名输入项在这里让位(在 REST 面上它同样不可达,
                    // 因为 ActionRequest.target 与 input 是两个字段)。
                    if (!ARG_TARGET.equals(entry.getKey())) {
                        properties.set(entry.getKey(), entry.getValue().deepCopy());
                    }
                });
            }
            JsonNode declaredRequired = declared.get("required");
            if (declaredRequired != null && declaredRequired.isArray()) {
                for (JsonNode name : declaredRequired) {
                    if (name.isTextual() && !ARG_TARGET.equals(name.asText())) {
                        required.add(name.asText());
                    }
                }
            }
        }
        return schema;
    }

    /** {@code arguments} 里除保留名之外的部分, 就是动作的 {@code input}。 */
    static ObjectNode inputOf(ObjectMapper objectMapper, JsonNode arguments) {
        ObjectNode input = objectMapper.createObjectNode();
        if (arguments == null || !arguments.isObject()) {
            return input;
        }
        arguments.fields().forEachRemaining(entry -> {
            if (!RESERVED_ARGUMENTS.contains(entry.getKey())) {
                input.set(entry.getKey(), entry.getValue());
            }
        });
        return input;
    }
}
