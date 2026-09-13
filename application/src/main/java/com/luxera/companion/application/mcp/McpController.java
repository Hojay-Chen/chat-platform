package com.luxera.companion.application.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.application.action.ActionStatusMapper;
import com.luxera.companion.application.principal.PrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * LAP v1 §MCP: <b>MCP 是一个适配器, 不是平台。</b>
 *
 * <p>它只做两件事, 而且就是这个类里仅有的两个分支:
 *
 * <pre>
 *   tools/list  →  动作发现   (ManifestRegistry 的目录, 见 {@link McpToolCatalog})
 *   tools/call  →  {@link ActionGateway#execute}
 * </pre>
 *
 * <p>没有第三件事。这里<em>不</em>判权限、<em>不</em>解析资源、<em>不</em>落任何库、<em>不</em>
 * 认识任何一个具体应用。一个 MCP 客户端和一个真人在协议上唯一的区别是"身份是从哪个头解析出来的";
 * 解析完就成了同一个 {@link ResolvedPrincipal}, 走同一条路。这也是为什么"给 MCP 单独开一条
 * 执行路径"在本平台里根本不是选项 —— 那条路迟早会少做一步主路径做的事(权限、归属、幂等),
 * 而少了哪一步都不会有人立刻发现。
 *
 * <p><b>协议状态只活在本进程里</b>(见 {@link McpSessions}), 绝不落 {@code application_session}。
 *
 * <p><b>HTTP 状态码的三档</b>(MCP 的 Streamable HTTP 传输):
 * <ul>
 *   <li><b>400</b> —— 这根本不是一条 JSON-RPC 消息(不是 JSON / 缺 method / jsonrpc 不是 2.0 /
 *       批量数组)。客户端写错了协议, 不是写错了参数。</li>
 *   <li><b>401 / 403</b> —— 身份没通过。判断与 REST 面共用
 *       {@link ActionStatusMapper#authenticationStatus}, 只是错误体的形状换成 JSON-RPC。</li>
 *   <li><b>200</b> —— 其余一切, <em>包括动作失败</em>。一次被拒的动作是一次成功的 JSON-RPC 调用,
 *       语义在 {@code result.isError} 里; 这是 MCP 自己的设计, 不要在这里另立一套。</li>
 * </ul>
 *
 * <p><b>通知不回东西。</b>{@code notifications/initialized} 与任何没有 {@code id} 的请求都回
 * {@code 202} 加空体 —— JSON-RPC 2.0 对通知不回应, 连错误都不回。一个"方法不认识"的通知正确
 * 的处理是安静地接受, 而不是回一个客户端按规范根本不会读的错误对象。
 *
 * <p><b>已知边界</b>(不是遗漏, 是没做): 本适配器只暴露 {@code tools/*}。MCP 的
 * {@code resources/list} / {@code resources/read} 没有实现 —— 平台的读模型
 * ({@code ResourceView}) 与 MCP 的资源模型形状不同, 硬套一层只会多出一份会漂移的映射。
 * 因此 {@code tools/call} 的 {@code target} 需要调用方自己知道(每个工具的 description 里都写了
 * 本应用的资源形如什么)。另: 未安装的应用<em>能</em>被列出但调不动 —— 发现不是授权。
 */
@Slf4j
@RestController
public class McpController {

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private static final String INSTRUCTIONS = """
            LAP 应用平台。应用的能力以工具形式暴露: 先 tools/list 找到动作, 再 tools/call 执行。
            每个工具都必须带 target(资源 URI), 它被写在各工具的 description 里。
            真人界面与 Agent 走的是同一条动作通道 —— 你在这里做的事, 用户会在同一个资源上看到。""";

    private final ActionGateway gateway;
    private final PrincipalResolvers principals;
    private final McpToolCatalog catalogue;
    private final McpSessions sessions;
    private final ObjectMapper objectMapper;

    public McpController(ActionGateway gateway,
                         PrincipalResolvers principals,
                         McpToolCatalog catalogue,
                         McpSessions sessions,
                         ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.principals = principals;
        this.catalogue = catalogue;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════ 入口 ═══════════════════════════

    @PostMapping(path = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> post(
            @RequestHeader(value = "X-Mcp-Principal", required = false) String mcpPrincipal,
            @RequestHeader(value = "X-Mcp-Service-Key", required = false) String serviceKey,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader,
            @RequestBody(required = false) String body) {

        String correlation = StringUtils.hasText(correlationHeader)
                ? correlationHeader
                : UUID.randomUUID().toString();

        // 1) 身份, 排在解析请求体之前。两条理由: 没通过鉴权的请求连它的 JSON 都不该被解析;
        //    以及"服务密钥没配 = MCP 完全关闭"必须在任何人都还没说上一句话时就生效 —— initialize
        //    也不例外, 否则握手本身就是一次公开的工具目录泄漏。
        ResolvedPrincipal principal;
        try {
            principal = principals.resolveMcp(mcpPrincipal, serviceKey, correlation);
        } catch (PrincipalResolver.PrincipalException e) {
            return error(ActionStatusMapper.authenticationStatus(e.code()), null,
                    McpJsonRpc.UNAUTHORIZED, e.code(), e.getMessage());
        }

        // 2) 协议会话(可选)。不带会话 id 的无状态用法完全合法 —— 身份在每个请求上重新验,
        //    会话里没有任何可供冒充的东西。带了就必须存在, 且必须是同一个 principal 开的。
        McpSessions.McpSession session = null;
        if (StringUtils.hasText(sessionHeader)) {
            session = sessions.find(sessionHeader).orElse(null);
            if (session == null) {
                return error(HttpStatus.NOT_FOUND, null, McpJsonRpc.SESSION_NOT_FOUND,
                        "MCP_SESSION_NOT_FOUND", "会话不存在或已失效: " + sessionHeader);
            }
            if (!session.belongsTo(principal)) {
                return error(HttpStatus.FORBIDDEN, null, McpJsonRpc.SESSION_PRINCIPAL_MISMATCH,
                        "MCP_SESSION_PRINCIPAL_MISMATCH",
                        "会话 " + sessionHeader + " 属于另一个 principal");
            }
            session = sessions.touch(session);
        }

        // 3) 解析。到这一步为止都没碰过请求体。
        if (!StringUtils.hasText(body)) {
            return error(HttpStatus.BAD_REQUEST, null, McpJsonRpc.PARSE_ERROR,
                    "PARSE_ERROR", "请求体为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            return error(HttpStatus.BAD_REQUEST, null, McpJsonRpc.PARSE_ERROR,
                    "PARSE_ERROR", "请求体不是合法 JSON");
        }

        String method = text(root, "method");
        if (root == null || !root.isObject()
                || !McpJsonRpc.VERSION.equals(text(root, "jsonrpc"))
                || !StringUtils.hasText(method)) {
            // "方法不认识"与"这根本不是一条 JSON-RPC 消息"是两回事, 所以这一条是 400。
            // 批量数组也落在这里: 本适配器只收单条, 不假装支持。
            return error(HttpStatus.BAD_REQUEST, idOf(root), McpJsonRpc.INVALID_REQUEST,
                    "INVALID_REQUEST", "只接受单个 JSON-RPC 2.0 请求对象(不支持批量)");
        }

        JsonNode id = root.get("id");
        JsonNode params = root.get("params");
        boolean notification = id == null || id.isNull();

        // 4) 分派。通知在每一个分支上都只回 202 —— 见类注释。
        return switch (method) {
            case "initialize" -> initialize(principal, params, id, notification);
            case "notifications/initialized" -> {
                if (session != null) {
                    sessions.markInitialized(session);
                }
                yield notification ? accepted() : ok(id, objectMapper.createObjectNode());
            }
            // 基础协议要求的 ping, 除此之外没有别的方法 —— 本适配器不做采样、不做根目录、不代理日志。
            case "ping" -> notification ? accepted() : ok(id, objectMapper.createObjectNode());
            case "tools/list" -> notification ? accepted() : ok(id, toolsList(params));
            case "tools/call" -> notification ? accepted()
                    : toolsCall(params, principal, id, idempotencyKey);
            default -> notification ? accepted()
                    : error(HttpStatus.OK, id, McpJsonRpc.METHOD_NOT_FOUND,
                            "METHOD_NOT_FOUND", "不支持的方法 " + method);
        };
    }

    /**
     * 终止协议会话。幂等于"这个会话不在了"。
     *
     * <p>不带服务密钥也能调: 它只能删掉协议状态(协议版本、客户端信息), 删不掉任何数据,
     * 而会话 id 是一个猜不中的 UUID。真正的数据在 {@code application_session} 里, 那个由
     * 平台自己的回收策略管, 与这里无关。
     */
    @DeleteMapping("/mcp")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionHeader) {
        if (!StringUtils.hasText(sessionHeader)) {
            return ResponseEntity.badRequest().build();
        }
        return sessions.close(sessionHeader)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ═══════════════════════════ 方法 ═══════════════════════════

    private ResponseEntity<JsonNode> initialize(ResolvedPrincipal principal, JsonNode params,
                                                JsonNode id, boolean notification) {
        if (notification) {
            // 通知型的 initialize 没有回执通道, 会话 id 递不回去 —— 那就不开, 而不是开一个
            // 客户端永远不知道的会话。
            return accepted();
        }
        McpSessions.McpSession session = sessions.open(principal, text(params, "protocolVersion"));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", session.protocolVersion());
        // 工具目录是静态的(启动时注册), 所以永远不会有 listChanged 通知。
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "luxera-lap");
        serverInfo.put("version", "1.0.0");
        result.put("instructions", INSTRUCTIONS);

        return ResponseEntity.ok().header(SESSION_HEADER, session.id()).body(response(id, result));
    }

    /**
     * 动作发现。可按能力或应用收窄 —— 这就是"先选能力, 再选应用, 再拿动作"这条链。
     *
     * <p>不分页: 收窄之后的目录本来就小, 一次给完比让客户端为一次翻页多跑一个来回划算。
     */
    private ObjectNode toolsList(JsonNode params) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (McpToolCatalog.McpTool tool : catalogue.tools(
                text(params, "capabilityId"), text(params, "applicationId"))) {
            tools.add(catalogue.describe(tool));
        }
        return result;
    }

    /**
     * 执行一个动作 —— 这个方法的全部内容就是"把 MCP 的说法翻成 LAP 的说法", 然后交给网关。
     *
     * <p>参数是<b>扁平</b>的: schema 怎么声明就怎么收, 除 {@code target} /
     * {@code expectedResourceVersion} / {@code _idempotencyKey} 三个平台保留名之外, 其余键
     * 原样收进 {@code ActionRequest.input}。声明扁平、收嵌套(或反过来)是这类适配器最经典的
     * 一处错位: 模型照着 schema 填了参数, 服务端却在另一层找它。
     */
    private ResponseEntity<JsonNode> toolsCall(JsonNode params, ResolvedPrincipal principal,
                                               JsonNode id, String headerKey) {
        if (params == null || !params.isObject()) {
            return error(HttpStatus.OK, id, McpJsonRpc.INVALID_PARAMS,
                    "INVALID_PARAMS", "tools/call 需要 params");
        }
        JsonNode arguments = params.get("arguments");
        if (arguments != null && !arguments.isNull() && !arguments.isObject()) {
            return error(HttpStatus.OK, id, McpJsonRpc.INVALID_PARAMS,
                    "INVALID_PARAMS", "arguments 必须是对象");
        }
        McpToolCatalog.McpTool tool = catalogue.find(text(params, "name")).orElse(null);
        if (tool == null) {
            return error(HttpStatus.OK, id, McpJsonRpc.INVALID_PARAMS,
                    "TOOL_NOT_FOUND", "没有这个工具: " + text(params, "name"));
        }

        // 幂等键: 请求头优先, 其次 arguments._idempotencyKey(给那些设不了请求头的客户端)。
        // 两处都没有就原样交出去 —— 网关会按动作的权限级别回 IDEMPOTENCY_KEY_REQUIRED,
        // 而不是由适配器另判一套"写动作要不要键"。
        String key = StringUtils.hasText(headerKey)
                ? headerKey
                : text(arguments, McpToolCatalog.ARG_IDEMPOTENCY_KEY);

        ActionRequest request = new ActionRequest(
                tool.actionId(),
                text(arguments, McpToolCatalog.ARG_TARGET),
                McpToolCatalog.inputOf(objectMapper, arguments),
                longOrNull(arguments, McpToolCatalog.ARG_EXPECTED_VERSION));

        ActionGateway.ActionExecution execution = gateway.execute(request, principal, key);
        ActionResponse response = execution.response();

        ObjectNode payload = objectMapper.valueToTree(response);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode textBlock = result.putArray("content").addObject();
        textBlock.put("type", "text");
        textBlock.put("text", payload.toPrettyString());
        // 文本那一份与结构化那一份是同一个对象 —— 客户端读哪一份都不会看到两种世界。
        result.set("structuredContent", payload);
        // 动作失败也是一次成功的 JSON-RPC 调用: 语义进 isError, 判定与 REST 面共用同一张表。
        result.put("isError", ActionStatusMapper.isError(response.status()));

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (execution.replayed()) {
            // 与 REST 面同一个头: "这次没有真的执行"。合起来看, 两条传输对重放的表述是一致的。
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(response(id, result));
    }

    // ═══════════════════════════ 信封 ═══════════════════════════

    private ResponseEntity<JsonNode> ok(JsonNode id, JsonNode result) {
        return ResponseEntity.ok(response(id, result));
    }

    private ObjectNode response(JsonNode id, JsonNode result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jsonrpc", McpJsonRpc.VERSION);
        node.set("id", id == null || id.isNull() ? NullNode.getInstance() : id.deepCopy());
        node.set("result", result);
        return node;
    }

    /**
     * JSON-RPC 错误对象。{@code label} 是平台自己的符号码(如 {@code TOOL_NOT_FOUND} /
     * {@code MCP_UNAUTHORIZED}), 放在 {@code data.code} 里 —— 这样调用方既能按协议码分支,
     * 也能拿到与 REST 面一致的、可以贴进工单的那个词。
     */
    private ResponseEntity<JsonNode> error(HttpStatus status, JsonNode id, int code,
                                           String label, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jsonrpc", McpJsonRpc.VERSION);
        node.set("id", id == null || id.isNull() ? NullNode.getInstance() : id.deepCopy());
        ObjectNode error = node.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (StringUtils.hasText(label)) {
            error.putObject("data").put("code", label);
        }
        return ResponseEntity.status(status).body(node);
    }

    private static ResponseEntity<JsonNode> accepted() {
        return ResponseEntity.accepted().build();
    }

    private static JsonNode idOf(JsonNode root) {
        return root != null && root.isObject() ? root.get("id") : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asLong() : null;
    }
}
