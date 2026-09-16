package com.luxera.companion.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * G8 —— 伴侣域的服务端转发。聊天平台对外只有**一个域名**(chat.luxera.top → 8081),
 * 浏览器永远只跟 8081 说话; 伴侣域(companions 详情 / memories / relationship / life /
 * self / reminders / notifications / user-model / state / reflections …)由 8081 在服务端
 * 转给仿真 Agent 平台(8091)。
 *
 * <p><b>为什么必须由后端转, 而不是让前端直连。</b>G5 曾让前端把伴侣域请求打上 {@code /agent}
 * 前缀直奔 8091(vite 双目标代理 + nginx {@code location /agent/api/})。那等于把 8091 变成
 * 浏览器可见的另一套 API: 两个服务各自暴露一部分界面所需的端点, "聊天平台的界面"实际由两个
 * 后端的并集拼成 —— 域名的边界和代码的边界对不上。用户指出的是这一点: 聊天平台调用仿真 Agent
 * 平台指的是**后端调用**。
 *
 * <p><b>分流不写表, 由 Spring 自己算。</b>本类只声明 {@code /api/companions} 与
 * {@code /api/companions/**} 两个兜底映射。8081 自己实现的端点(会话/消息/事件流/线程/会话内应用)
 * 有更精确的映射, Spring 的 {@code AntPatternComparator} 让它们赢过 {@code /**} ——
 * 于是"哪些留下、哪些转发"是从 HandlerMapping 推导出来的, 而不是一张要人工维护的路径清单。
 * 这与 G5 的 {@code route()} 有本质区别: 那张表是**猜**的, 靠"路径段"判, 一旦某个段下两端
 * 都有端点就必然判错。
 *
 * <p><b>这不是理论问题 —— G5 的段规则确实把主聊天链路判错了。</b>
 * {@code POST /api/companions/{id}/conversations/first} 与
 * {@code POST /api/companions/{id}/conversations/{cid}/chat} 都是 8091 的端点,
 * 但它们的路径段是 {@code conversations} —— G5 把整段划给了 8081, 于是前端拿 404。
 * 实测: 同一 JWT 打 8081 得 404、打 8091 得 500(端点存在, 只是伴侣不存在), 端点归属
 * 一目了然。本类按"8081 到底实现了什么"判, 不会犯这个错。
 *
 * <p><b>转发的是调用者的 JWT, 不是服务身份。</b>归属校验("这个伴侣是不是你的")必须由
 * 8091 自己做 —— 若 8081 改用 HMAC 服务身份代签, 它就成了越权代理(confused deputy):
 * 任何能过 8081 鉴权的请求, 都能借服务身份读到**别人**的伴侣。两服务共用同一个
 * JWT_SECRET, 所以把 {@code Authorization} 原样透传, 8091 侧的用户级鉴权原封不动生效。
 * 这也意味着 8081 在这条链路上不新增任何授权判断 —— 它只搬字节。
 *
 * <p><b>不缓冲响应。</b>{@code /conversations/{cid}/chat} 是 SSE(SseEmitter, 300s),
 * 边想边说。任何整包缓冲都会把流式聊天变成"等想完再一次性吐出", 认知链的可观测性就没了。
 * 所以逐块 copy + 每块 flush, 且读超时默认不限(0) —— 上游自己会在 300s 收尾。
 *
 * <p>HTTP 客户端用 JDK {@code HttpURLConnection}, 与 {@link HttpCompanionDirectoryAdapter}
 * (G3)、{@code RemoteApplicationInvoker}(R14) 同一先例, 不引新依赖。
 */
@Slf4j
@RestController
public class CompanionDomainProxyController {

    /** 基地址与 G3 的目录适配器共用一个配置项 —— 指向 8091 的只有这一处。 */
    private final String baseUrl;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    /**
     * 逐跳头(RFC 7230 §6.1)不能转发: 它们描述的是"这一跳"的连接, 转给下一跳会撒谎。
     * {@code Host} 与 {@code Content-Length} 单列 —— 前者必须由 HttpURLConnection 按目标
     * 重算, 后者在流式转发下长度未知(用 chunked 代替)。
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length");

    public CompanionDomainProxyController(
            @Value("${app.agent-platform.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${app.agent-platform.proxy-connect-timeout-ms:5000}") int connectTimeoutMillis,
            // 0 = 不限。SSE 的响应体在认知链跑完前一直不结束, 任何固定读超时都会把
            // 长回复掐断; 上游 SseEmitter 自己 300s 收尾, 不依赖这一层兜底。
            @Value("${app.agent-platform.proxy-read-timeout-ms:0}") int readTimeoutMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * 伴侣域的**兜底**映射。8081 已实现的更精确路径(见类注释)在此**之前**被 HandlerMapping
     * 选中, 到不了这里; 到达这里的都是 8091 的端点。
     *
     * <p>{@code /api/companions} 本身(列表/创建)也要单列 —— {@code /**} 匹配不到无子路径的
     * 形式。G1 把伴侣 CRUD 迁去了 8091, 8081 没有这个映射。
     */
    @RequestMapping({"/api/companions", "/api/companions/**"})
    public void proxy(HttpServletRequest req, HttpServletResponse resp) {
        String target = baseUrl + req.getRequestURI()
                + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(target).toURL().openConnection();
            conn.setRequestMethod(req.getMethod());
            conn.setConnectTimeout(connectTimeoutMillis);
            conn.setReadTimeout(readTimeoutMillis);
            // 3xx 是上游的语义(如 Location 指向自己的 docs), 由调用方决定跟不跟
            conn.setInstanceFollowRedirects(false);
            copyRequestHeaders(req, conn);
            forwardRequestBody(req, conn);
            relayResponse(conn, resp);
        } catch (IOException e) {
            // 只记一行 —— 8091 缺席时这条会被每个前端轮询打到, 堆栈刷屏没有信息量
            log.warn("[CompanionProxy] {} {} → 8091 失败: {}",
                    req.getMethod(), req.getRequestURI(), e.toString());
            sendBadGateway(resp, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void copyRequestHeaders(HttpServletRequest req, HttpURLConnection conn) {
        for (Enumeration<String> names = req.getHeaderNames(); names.hasMoreElements(); ) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) continue;
            for (Enumeration<String> values = req.getHeaders(name); values.hasMoreElements(); ) {
                conn.addRequestProperty(name, values.nextElement());
            }
        }
    }

    /**
     * 有体才写体。{@code HttpURLConnection} 上一旦调用 {@code getOutputStream()} 就会把方法
     * 改成 POST —— 对无体的 GET/DELETE 是静默的方法篡改, 所以必须先判有没有体。
     * 长度未知用 chunked(如 SSE 请求前端的流式上行), 已知则原样定长, 少一层分块开销。
     */
    private void forwardRequestBody(HttpServletRequest req, HttpURLConnection conn) throws IOException {
        long declared = req.getContentLengthLong();
        boolean chunkedIn = "chunked".equalsIgnoreCase(req.getHeader("Transfer-Encoding"));
        if (declared <= 0 && !chunkedIn) return;

        conn.setDoOutput(true);
        if (declared < 0) conn.setChunkedStreamingMode(8192);
        try (InputStream in = req.getInputStream(); OutputStream out = conn.getOutputStream()) {
            copy(in, out, false);
        }
    }

    private void relayResponse(HttpURLConnection conn, HttpServletResponse resp) throws IOException {
        int status = conn.getResponseCode();
        resp.setStatus(status);
        for (var entry : conn.getHeaderFields().entrySet()) {
            String name = entry.getKey();
            // 首行(HTTP/1.1 200 OK)在 headerFields 里的 key 是 null
            if (name == null || HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) continue;
            for (String value : entry.getValue()) {
                resp.addHeader(name, value);
            }
        }
        // 先提交响应头。SSE 靠这个把 text/event-stream 立刻送到浏览器, 否则它会跟
        // 第一个事件一起卡在 Tomcat 的 8KB 缓冲里 —— 表现是"连上了但迟迟没有反应"。
        resp.flushBuffer();

        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return;
        try (InputStream body = in; OutputStream out = resp.getOutputStream()) {
            // flush=true: 每块都推出去, 不做整包缓冲(理由见类注释)
            copy(body, out, true);
        }
    }

    private void copy(InputStream in, OutputStream out, boolean flushEachChunk) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (flushEachChunk) out.flush();
        }
    }

    /** 8091 不可达 = 网关错误(502), 不是 500 —— 8081 自己没出错, 是下游没接上。 */
    private void sendBadGateway(HttpServletResponse resp, IOException cause) {
        // 流已经开始写就无法再改状态码了(客户端已收到部分响应); 此时只能让连接断掉,
        // 由前端 SSE 重连逻辑处理 —— 硬写一个 502 反而会污染已经发出去的事件流。
        if (resp.isCommitted()) return;
        try {
            resp.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            resp.setContentType("application/json; charset=utf-8");
            String msg = cause.getMessage() == null ? cause.getClass().getSimpleName()
                    : cause.getMessage().replace("\"", "'");
            resp.getOutputStream().write(
                    ("{\"error\":\"仿真 Agent 平台暂不可达\",\"detail\":\"" + msg + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 连接已断, 无处可写
        }
    }
}
