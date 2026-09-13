package com.luxera.companion.application.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LAP v1 §MCP: JSON-RPC 2.0 的信封 —— 只有常量, 和"这一句是不是通知"这一个判断。
 *
 * <p>放进一个类而不是散在控制器里的理由和 {@code ActionStatusMapper} 一样: <b>错误码是协议</b>。
 * 一个客户端靠 {@code -32601} 知道"服务端不认识这个方法", 靠 {@code -32602} 知道"参数是我写错了";
 * 这两句话若在几处各写一遍, 迟早有一处写成 {@code -32603}, 于是客户端的重试逻辑开始做错事。
 *
 * <p><b>动作失败不是这里的错误码。</b>一个被拒的动作是一次<em>成功的</em> JSON-RPC 调用,
 * 它的结果装在 {@code result.isError} 里(见 {@link com.luxera.companion.application.action.ActionStatusMapper#isError})。
 * 这里列的全是"这句话本身没被听懂"。
 */
public final class McpJsonRpc {

    private McpJsonRpc() {
    }

    public static final String VERSION = "2.0";

    // ── JSON-RPC 2.0 预定义 ──

    /** 收到的不是 JSON。 */
    public static final int PARSE_ERROR = -32700;
    /** 是 JSON, 但不是一条合法的 JSON-RPC 消息(缺 method / jsonrpc 不是 2.0 / 批量数组)。 */
    public static final int INVALID_REQUEST = -32600;
    /** 方法名不认识。 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 方法认识, 参数不对(工具名不存在、arguments 不是对象)。 */
    public static final int INVALID_PARAMS = -32602;

    // ── MCP 传输层自己的三个 ──

    /**
     * 身份没通过。JSON-RPC 没有"未认证"这个预定义码, 用实现自定义区间里的一个。
     * 具体是 401 还是 403 由 HTTP 状态码说了算 —— 那正是客户端该看的地方。
     */
    public static final int UNAUTHORIZED = -32000;

    /** {@code Mcp-Session-Id} 指向一个不存在的会话(或进程重启后失效了)。 */
    public static final int SESSION_NOT_FOUND = -32001;
    /** 会话存在, 但它是<em>另一个 principal</em> 开的。 */
    public static final int SESSION_PRINCIPAL_MISMATCH = -32002;

    /**
     * 一条解析出来的请求。
     *
     * <p>{@code id} 缺席或为 {@code null} 即<b>通知</b>。JSON-RPC 对通知<em>不回任何东西</em> ——
     * 连错误都不回。所以这个判断必须排在所有分支之前: 一个"方法名不认识"的通知, 正确的处理是
     * 安静地 202, 而不是回一个客户端按规范根本不会读的错误对象。
     */
    public record Request(String jsonrpc, JsonNode id, String method, JsonNode params) {

        public boolean isNotification() {
            return id == null || id.isNull();
        }
    }
}
