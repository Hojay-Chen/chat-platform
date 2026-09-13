package com.luxera.companion.application.principal;

import com.luxera.companion.contracts.application.PrincipalType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LAP v1: MCP 来源的身份。
 *
 * <p>MCP 的传输里没有 JWT —— 它是一个 JSON-RPC 端点, 客户端可能是 Claude Desktop、某个
 * IDE、或另一个 Agent 运行时。所以身份靠两样东西: 调用方自报的 principal
 * ({@code X-Mcp-Principal: AGENT:companion-123}) 与一个<em>服务密钥</em>
 * ({@code X-Mcp-Service-Key})。
 *
 * <p>密钥没配({@code app.lap.mcp.service-key} 为空)时, 这个解析器<b>完全不支持任何请求</b> ——
 * 也就是 MCP 端点默认关闭。这不是遗漏而是默认值: 一个默认打开、默认无鉴权的公网动作入口,
 * 是这类系统里最常见也最昂贵的一个错误。
 *
 * <p>principal 类型只能是 AGENT / APPLICATION —— 一个外部 MCP 客户端声称自己是真人, 平台没有
 * 任何依据可以核实(真人身份靠 JWT 承载)。直接拒绝, 而不是让它以 HUMAN 身份享有一切。
 */
@Component
public class McpPrincipalResolver implements PrincipalResolver {

    private final String configuredServiceKey;

    public McpPrincipalResolver(@Value("${app.lap.mcp.service-key:}") String configuredServiceKey) {
        this.configuredServiceKey = configuredServiceKey == null ? "" : configuredServiceKey.trim();
    }

    @Override
    public String source() {
        return ResolvedPrincipal.SOURCE_MCP;
    }

    @Override
    public boolean supports(PrincipalRequest request) {
        return request != null
                && (StringUtils.hasText(request.mcpPrincipalHeader())
                    || StringUtils.hasText(request.mcpServiceKey()));
    }

    @Override
    public ResolvedPrincipal resolve(PrincipalRequest request) {
        if (configuredServiceKey.isEmpty()) {
            throw new PrincipalException("MCP_DISABLED",
                    "MCP 端点未启用(app.lap.mcp.service-key 未配置)");
        }
        if (!configuredServiceKey.equals(request.mcpServiceKey())) {
            throw new PrincipalException("MCP_UNAUTHORIZED", "MCP 服务密钥不正确");
        }
        String declared = request.mcpPrincipalHeader();
        if (!StringUtils.hasText(declared)) {
            throw new PrincipalException("MCP_PRINCIPAL_REQUIRED", "缺少 X-Mcp-Principal");
        }
        String[] parts = declared.split(":", 2);
        if (parts.length != 2 || !StringUtils.hasText(parts[1])) {
            throw new PrincipalException("MCP_PRINCIPAL_REQUIRED",
                    "X-Mcp-Principal 必须是 <TYPE>:<id>, 收到 " + declared);
        }

        PrincipalType type;
        try {
            type = PrincipalType.valueOf(parts[0].trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PrincipalException("MCP_PRINCIPAL_INVALID", "未知的 principal 类型 " + parts[0]);
        }
        if (type == PrincipalType.HUMAN) {
            throw new PrincipalException("MCP_PRINCIPAL_INVALID",
                    "MCP 客户端不得声称自己是 HUMAN —— 真人身份只能由 JWT 承载");
        }

        String principalId = parts[1].trim();
        return new ResolvedPrincipal(type, principalId,
                type == PrincipalType.AGENT ? principalId : null,
                null, null, request.correlationId(), ResolvedPrincipal.SOURCE_MCP);
    }
}
