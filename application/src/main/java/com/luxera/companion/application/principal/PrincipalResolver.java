package com.luxera.companion.application.principal;

import com.luxera.companion.contracts.application.PrincipalType;

/**
 * LAP v1: {@code HUMAN} / {@code AGENT} / {@code SYSTEM} / {@code APPLICATION} 的落地方式。
 *
 * <p>三种来源, 三个实现, <b>刻意不合并成一个"尽力而为"的解析器</b>:
 *
 * <ul>
 *   <li>{@code JwtPrincipalResolver} —— REST。令牌里的 {@code ptype} 说了算;
 *       历史令牌缺 claim 才回落 HUMAN。</li>
 *   <li>{@code McpPrincipalResolver} —— MCP。没有 JWT, 靠 {@code X-Mcp-Principal}
 *       + 服务密钥, 密钥不对直接拒。</li>
 *   <li>{@code InternalPrincipalResolver} —— DH 进程内。{@code InvocationContext} 里
 *       必须<em>显式</em>写了 principal 类型, 没写就抛异常。</li>
 * </ul>
 *
 * <p>合并成一个解析器的话, 三者会共享一条"取不到就用默认值"的兜底路径, 而那条路径正是
 * "Agent 悄悄变成真人"的入口 —— 一个 Agent 只要拿不到自己的类型, 就会以真人的身份通过所有
 * 真人专属的检查。分开写, 每个来源对自己不知道的情况只能拒绝。
 */
public interface PrincipalResolver {

    /** 本解析器负责的来源标记, 落到 {@link ResolvedPrincipal#source()}。 */
    String source();

    /**
     * 能否解析这个上下文。三者互斥, REST 适配器按固定顺序问一遍。
     */
    boolean supports(PrincipalRequest request);

    ResolvedPrincipal resolve(PrincipalRequest request);

    /** 三方共用的入参 —— 三种来源都从这一小撮原始材料里取自己的那部分。 */
    record PrincipalRequest(String authorizationHeader,
                            String mcpPrincipalHeader,
                            String mcpServiceKey,
                            com.luxera.companion.contracts.application.InvocationContext internal,
                            String correlationId) {

        public static PrincipalRequest ofHeader(String authorizationHeader, String correlationId) {
            return new PrincipalRequest(authorizationHeader, null, null, null, correlationId);
        }

        public static PrincipalRequest ofInternal(
                com.luxera.companion.contracts.application.InvocationContext internal) {
            return new PrincipalRequest(null, null, null, internal,
                    internal == null ? null : internal.correlationId());
        }

        public static PrincipalRequest ofMcp(String mcpPrincipalHeader, String mcpServiceKey,
                                            String correlationId) {
            return new PrincipalRequest(null, mcpPrincipalHeader, mcpServiceKey, null, correlationId);
        }

        /**
         * 开发者面: <b>同一个端点上可能来两种人</b> —— 带 JWT 的真人(会被
         * {@code ApplicationLifecycleService} 以 {@code LIFECYCLE_FORBIDDEN} 挡下)与带服务密钥的
         * 平台/开发者身份。两者都交给同一条解析链按固定顺序裁决, 而不是在控制器里写
         * "有 MCP 头就走 MCP, 否则当真人" —— 后者会让一个既没有 JWT 也没有服务密钥的请求
         * 以某个默认身份通过。
         */
        public static PrincipalRequest ofDeveloper(String authorizationHeader,
                                                   String mcpPrincipalHeader,
                                                   String mcpServiceKey,
                                                   String correlationId) {
            return new PrincipalRequest(authorizationHeader, mcpPrincipalHeader, mcpServiceKey,
                    null, correlationId);
        }
    }

    /** 解析失败一律是这个 —— 翻成 401/403, 绝不"继续以匿名身份执行"。 */
    class PrincipalException extends RuntimeException {

        private final String code;

        public PrincipalException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** MCP 与服务密钥场景下的默认 principal 类型。 */
    PrincipalType DEFAULT_MCP_TYPE = PrincipalType.AGENT;
}
