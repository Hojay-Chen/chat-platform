package com.luxera.companion.application.principal;

import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;

/**
 * LAP v1: 一次请求解出来的调用方身份。
 *
 * <p>刻意<em>不是</em> {@link InvocationContext} 本身: 那个类型是"进程内调用方告诉平台的",
 * 这个是"平台自己确认过的"。两者形状相同而来源相反 —— 混成一个类型, 迟早会有人把
 * 请求体或请求头里的东西直接塞进 {@code InvocationContext} 当成已认证身份用。
 *
 * <p>{@link #source()} 单独记着是谁做的认证({@code JWT} / {@code MCP} / {@code INTERNAL}),
 * 审计里能回答"这个身份是怎么来的", 而不是只有"是谁"。
 */
public record ResolvedPrincipal(PrincipalType type,
                                String principalId,
                                String companionId,
                                String userId,
                                String sessionId,
                                String correlationId,
                                String source) {

    public static final String SOURCE_JWT = "JWT";
    public static final String SOURCE_MCP = "MCP";
    public static final String SOURCE_INTERNAL = "INTERNAL";

    public InvocationContext toInvocationContext() {
        return new InvocationContext(type, principalId, companionId, userId, sessionId, correlationId);
    }

    public String typeName() {
        return type == null ? null : type.name();
    }
}
