package com.luxera.companion.application.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestParser;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v1 §Adapter-Transport: <b>REMOTE 应用</b> —— 平台这一侧转发一次动作的全过程。
 *
 * <p>用了真的 {@link HttpServer}(JDK 自带, 不引新依赖), 不是打桩的 HTTP 客户端。理由是这一层
 * 要证明的恰好是桩会替我们跳过的东西: 头有没有真的写出去、签名在对面能不能验过、超时到底是
 * 超时还是"连接被拒"、状态码有没有映射对。一个 mock 的客户端能让上面每一条都通过, 而线上
 * 一个都成立不了。
 *
 * <p><b>关于转发的那把幂等键。</b> 转发的是<em>派生</em>出来的键, 不是调用方的。调用方的键只在
 * 本平台的 {@code (principal, key)} 作用域里唯一, 两个不同的人各自用了 {@code "1"} 就会在远端
 * 撞成同一次调用。派生键是"这一次逻辑调用"的确定函数, 所以重试仍然幂等, 不同调用方之间则
 * 必然不同 —— 两边想要的那两件事只有这样才同时拿得到。
 */
class RemoteApplicationInvokerTest {

    private static final String SECRET = "s3cr3t-for-probe";
    private static final String PROBE = "probe";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ManifestParser parser = new ManifestParser(objectMapper);

    private HttpServer server;
    private int port;
    private Response reply = Response.ok("{\"result\":{\"pong\":true}}");
    private long delayMillis;
    private final List<Captured> captured = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lap/actions:execute", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        captured.add(Captured.of(exchange));
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // ─────────────────────────── 转发成功 ───────────────────────────

    @Test
    void aSuccessfulCallCarriesTheActionTheTargetAndTheInput() throws Exception {
        ActionOutcome outcome = invoker(3000).invoke(manifest(), context("probe.touch", agent("dh-1")));

        assertTrue(outcome.succeeded(), outcome.errorCode());
        assertEquals(true, outcome.result().path("pong").asBoolean());

        JsonNode body = objectMapper.readTree(captured.get(0).body());
        assertEquals("probe.touch", body.path("action").asText());
        assertEquals("com.luxera.probe", body.path("applicationId").asText());
        assertEquals("1.0.0", body.path("version").asText());
        assertEquals("probe://probe/1", body.path("target").asText());
        assertEquals(7, body.path("input").path("note").asInt());
        assertEquals("AGENT", body.path("principal").path("type").asText());
        assertEquals("dh-1", body.path("principal").path("id").asText());
        assertEquals("dh-1", body.path("principal").path("companionId").asText());
        assertEquals("corr-1", body.path("correlationId").asText());
    }

    /** 远端看到的是自己的身份与动作, 不是"某个平台在调我"这种没有信息量的东西。 */
    @Test
    void theHeadersIdentifyTheApplicationTheActionAndTheCaller() {
        invoker(3000).invoke(manifest(), context("probe.touch", agent("dh-1")));

        Captured call = captured.get(0);
        assertEquals("com.luxera.probe", call.header("X-Lap-Application"));
        assertEquals("1.0.0", call.header("X-Lap-Version"));
        assertEquals("probe.touch", call.header("X-Lap-Action"));
        assertEquals("AGENT:dh-1", call.header("X-Lap-Principal"));
        assertNotNull(call.header("X-Lap-Timestamp"));
    }

    /**
     * 签名是 HMAC-SHA256 over {@code timestamp + "." + body} —— 把时间戳纳入签名, 一次被截获的
     * 请求才不能在没有密钥的情况下被无限期重放。
     */
    @Test
    void theSignatureVerifiesOverTheTimestampAndTheExactBodyBytes() {
        invoker(3000).invoke(manifest(), context("probe.touch", agent("dh-1")));

        Captured call = captured.get(0);
        String expected = RemoteSignature.sign(SECRET, call.header("X-Lap-Timestamp"), call.body());
        assertEquals(expected, call.header(RemoteSignature.HEADER_SIGNATURE));
        assertTrue(call.header(RemoteSignature.HEADER_SIGNATURE).startsWith("sha256="));
    }

    /** 一个只认裸 result 的远端也是合规的 —— 两种回法都认, 不认第二种会把照文档实现的远端判成失败。 */
    @Test
    void aBareResultBodyAndAnEnvelopeAreBothAccepted() {
        reply = Response.ok("{\"pong\":true}");
        assertTrue(invoker(3000).invoke(manifest(), context("probe.ping", agent("dh-1"))).succeeded());

        reply = Response.ok("{\"result\":{\"pong\":true}}");
        assertTrue(invoker(3000).invoke(manifest(), context("probe.ping", agent("dh-1"))).succeeded());
    }

    // ─────────────────────────── 幂等键 ───────────────────────────

    @Test
    void theForwardedIdempotencyKeyIsDerivedAndStableAcrossRetries() {
        RemoteApplicationInvoker invoker = invoker(3000);
        ActionHandlerContext ctx = context("probe.touch", agent("dh-1"));

        String first = invoker.deriveIdempotencyKey(manifest(), ctx);
        String second = invoker.deriveIdempotencyKey(manifest(), ctx);
        assertEquals(first, second, "同一次逻辑调用的重试必须得到同一把键, 否则远端会重复执行");

        invoker.invoke(manifest(), ctx);
        assertEquals(first, captured.get(0).header("Idempotency-Key"),
                "转发出去的就是派生键, 不是调用方随手起的那把");
    }

    @Test
    void twoCallersNeverShareADerivedKey() {
        RemoteApplicationInvoker invoker = invoker(3000);

        String alice = invoker.deriveIdempotencyKey(manifest(), context("probe.touch", agent("dh-1")));
        String bob = invoker.deriveIdempotencyKey(manifest(), context("probe.touch", agent("dh-2")));

        assertNotEquals(alice, bob);
    }

    // ─────────────────────────── 超时与错误 ───────────────────────────

    @Test
    void aRemoteThatNeverAnswersTimesOutInsteadOfHangingTheCaller() {
        delayMillis = 800;

        ActionOutcome outcome = invoker(200).invoke(manifest(), context("probe.touch", agent("dh-1")));

        assertEquals(ActionStatus.FAILED, outcome.status());
        assertEquals("REMOTE_TIMEOUT", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("200ms"));
    }

    /** 一个连不上的远端是"暂时不可用", 不是"你没权限" —— 调用方该做的是稍后重试。 */
    @Test
    void anUnreachableRemoteIsUnavailableRatherThanDenied() {
        server.stop(0);

        ActionOutcome outcome = invoker(3000).invoke(manifest(), context("probe.touch", agent("dh-1")));

        assertEquals(ActionStatus.FAILED, outcome.status());
        assertEquals("REMOTE_UNAVAILABLE", outcome.errorCode());
    }

    @Test
    void httpStatusesBecomeTheSameActionStatusesTheRestSurfaceUses() {
        assertEquals(ActionStatus.STATE_CONFLICT, callWithStatus(409).status());
        assertEquals(ActionStatus.NOT_FOUND, callWithStatus(404).status());
        assertEquals(ActionStatus.DENIED, callWithStatus(403).status());
        assertEquals(ActionStatus.DENIED, callWithStatus(401).status());
        assertEquals(ActionStatus.INVALID_ARGUMENT, callWithStatus(400).status());
        assertEquals(ActionStatus.INVALID_ARGUMENT, callWithStatus(422).status());
        assertEquals(ActionStatus.FAILED, callWithStatus(500).status());
    }

    /** 200 里带 error 信封 = 远端自己判定的业务失败, 不能因为 HTTP 是 2xx 就当成功。 */
    @Test
    void anErrorEnvelopeInsideA200IsStillAFailure() {
        reply = Response.ok("{\"error\":{\"code\":\"REMOTE_RULE\",\"message\":\"这一步不合规则\"}}");

        ActionOutcome outcome = invoker(3000).invoke(manifest(), context("probe.touch", agent("dh-1")));

        assertEquals(ActionStatus.FAILED, outcome.status());
        assertEquals("REMOTE_RULE", outcome.errorCode());
    }

    // ─────────────────────────── 平台侧配置缺失 ───────────────────────────

    /**
     * {@code authRef} 是<em>名字</em>不是密钥。名字解析不到是平台没部署好 —— 用 FAILED 而不是
     * DENIED: 调用方的权限没有任何问题, 报 403 会让人去查权限, 查半天发现是配置漏了。
     */
    @Test
    void anUnresolvableAuthRefIsAPlatformProblemNotAClientOne() {
        MockEnvironment empty = new MockEnvironment();
        RemoteApplicationInvoker invoker = new RemoteApplicationInvoker(objectMapper, empty, 3000);

        ActionOutcome outcome = invoker.invoke(manifest(), context("probe.touch", agent("dh-1")));

        assertEquals(ActionStatus.FAILED, outcome.status());
        assertEquals("REMOTE_AUTH_UNRESOLVED", outcome.errorCode());
        assertTrue(captured.isEmpty(), "没密钥就不该发出任何请求");
    }

    @Test
    void aManifestWithoutAnEndpointIsRejectedBeforeAnyNetworkCall() {
        String json = manifestJson(port).replace("\"http://127.0.0.1:" + port + "/lap/actions:execute\"",
                "null");
        ApplicationManifest broken = parser.parse(json);

        ActionOutcome outcome = invoker(3000).invoke(broken, context("probe.touch", agent("dh-1")));

        assertEquals("REMOTE_ENDPOINT_MISSING", outcome.errorCode());
        assertTrue(captured.isEmpty());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private ActionOutcome callWithStatus(int status) {
        reply = new Response(status, "{\"message\":\"远端说不行\"}");
        return invoker(3000).invoke(manifest(), context("probe.touch", agent("dh-1")));
    }

    private RemoteApplicationInvoker invoker(int timeoutMillis) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.lap.remote.auth." + PROBE, SECRET);
        return new RemoteApplicationInvoker(objectMapper, environment, timeoutMillis);
    }

    private ApplicationManifest manifest() {
        return parser.parse(manifestJson(port));
    }

    private ActionHandlerContext context(String actionId, InvocationContext principal) {
        ApplicationManifest manifest = manifest();
        ApplicationManifest.ActionDecl spec = manifest.action(actionId).orElseThrow();
        JsonNode input = objectMapper.createObjectNode().put("note", 7);
        return new ActionHandlerContext(manifest.applicationId(), manifest.version(), manifest, spec,
                new ActionRequest(actionId, "probe://probe/1", input, null),
                principal, null, objectMapper);
    }

    private static InvocationContext agent(String companionId) {
        return new InvocationContext(PrincipalType.AGENT, companionId, companionId,
                "user-1", null, "corr-1");
    }

    /**
     * 与 {@code src/test/resources/applications/remote-probe} 里那份同源, 只有端口是这里插进去的。
     * 内联而不是读文件: 这个测试要的是"随便指向哪个端口", 而固定文件里写不下一个临时端口。
     */
    private static String manifestJson(int port) {
        return """
                {
                  "identity": {
                    "id": "com.luxera.probe",
                    "name": "远端探针",
                    "version": "1.0.0",
                    "description": "测试夹具",
                    "category": "diagnostics"
                  },
                  "capabilities": [
                    { "id": "probe.diagnostics", "title": "远端探针", "description": "只有测试会装它" }
                  ],
                  "actions": [
                    {
                      "id": "probe.ping",
                      "capability": "probe.diagnostics",
                      "title": "打个招呼",
                      "description": "只读",
                      "permission": "READ",
                      "risk": "NONE",
                      "attention": "NONE"
                    },
                    {
                      "id": "probe.touch",
                      "capability": "probe.diagnostics",
                      "title": "记一下",
                      "description": "有副作用",
                      "permission": "WRITE",
                      "risk": "LOW",
                      "attention": "AWARE"
                    }
                  ],
                  "resources": [
                    {
                      "type": "probe.state",
                      "uriTemplate": "probe://probe/{id}",
                      "backing": "APP_OWNED"
                    }
                  ],
                  "events": [],
                  "permissions": [],
                  "runtime": {
                    "type": "REMOTE",
                    "remote": {
                      "baseUrl": "http://127.0.0.1:%d/lap/actions:execute",
                      "authRef": "%s"
                    }
                  }
                }
                """.formatted(port, PROBE);
    }

    private record Response(int status, String body) {
        static Response ok(String body) {
            return new Response(200, body);
        }
    }

    private record Captured(String body, java.util.Map<String, String> headers) {

        static Captured of(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name, values.get(0));
                }
            });
            return new Captured(body, headers);
        }

        /** {@code HttpURLConnection} 会把头名字按自己的规矩改写, 大小写照它实际发出去的样子找。 */
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }
}
