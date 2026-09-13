package com.luxera.companion.application.principal;

import com.luxera.companion.contracts.application.InvocationContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LAP v1: 按来源挑解析器。
 *
 * <p>顺序固定且互斥: 进程内 → JWT → MCP。进程内排在最前面是因为它是唯一一个"调用方已经
 * 知道自己是谁"的来源, 不该被一个恰好也带着 Authorization 头的请求劫持。JWT 在 MCP 之前是
 * 因为能签出 JWT 的身份比服务密钥更强。
 *
 * <p>三个都不认 → <b>拒绝</b>, 不返回匿名身份。一个没有身份的动作请求在 LAP 里不存在:
 * 权限模型的第一维就是 Principal, 没有 Principal 的动作无法被判定, 放它过去等于跳过整张表。
 */
@Service
public class PrincipalResolvers {

    private final List<PrincipalResolver> resolvers;

    public PrincipalResolvers(InternalPrincipalResolver internal,
                              JwtPrincipalResolver jwt,
                              McpPrincipalResolver mcp) {
        this.resolvers = List.of(internal, jwt, mcp);
    }

    public ResolvedPrincipal resolve(PrincipalResolver.PrincipalRequest request) {
        for (PrincipalResolver resolver : resolvers) {
            if (resolver.supports(request)) {
                return resolver.resolve(request);
            }
        }
        throw new PrincipalResolver.PrincipalException("UNIDENTIFIED_PRINCIPAL",
                "无法从请求中确定调用方身份");
    }

    public ResolvedPrincipal resolveInternal(InvocationContext ctx) {
        return resolve(PrincipalResolver.PrincipalRequest.ofInternal(ctx));
    }

    public ResolvedPrincipal resolveHeader(String authorizationHeader, String correlationId) {
        return resolve(PrincipalResolver.PrincipalRequest.ofHeader(authorizationHeader, correlationId));
    }

    public ResolvedPrincipal resolveMcp(String principalHeader, String serviceKey, String correlationId) {
        return resolve(PrincipalResolver.PrincipalRequest.ofMcp(principalHeader, serviceKey, correlationId));
    }
}
