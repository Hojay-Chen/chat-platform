package com.luxera.companion.application.principal;

import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内身份<b>不接受默认值</b>。
 *
 * <p>这是本轮最容易被"顺手写得更宽松一点"的地方: 调用发生在同一个 JVM, 调用方就是数字人自己,
 * 于是 {@code ctx.principalType() == null ? AGENT : ctx.principalType()} 看起来完全无害。
 *
 * <p>它之所以危险, 是因为 {@code DefaultActionsRuntime} 时代恰恰是反过来的 —— 类型默认 HUMAN,
 * Agent 的调用没有带类型, 于是一路以真人的身份通过检查。默认值一旦存在就会有人依赖它, 依赖它
 * 的人多了, 类型就不再是事实而是一个装饰。所以这条测试断言的是<b>抛异常</b>, 而不是"回落成
 * 某个合理的值"。
 */
class InternalPrincipalResolverTest {

    private final InternalPrincipalResolver resolver = new InternalPrincipalResolver();

    @Test
    void missingPrincipalTypeIsRejectedNotDefaulted() {
        InvocationContext ctx = new InvocationContext(
                null, "companion-1", "companion-1", null, null, "corr-1");

        PrincipalResolver.PrincipalException e = assertThrows(PrincipalResolver.PrincipalException.class,
                () -> resolver.resolve(PrincipalResolver.PrincipalRequest.ofInternal(ctx)));
        assertEquals("INTERNAL_PRINCIPAL_TYPE_REQUIRED", e.code());
    }

    @Test
    void missingPrincipalIdIsRejected() {
        InvocationContext ctx = new InvocationContext(
                PrincipalType.AGENT, "  ", "companion-1", null, null, "corr-1");

        PrincipalResolver.PrincipalException e = assertThrows(PrincipalResolver.PrincipalException.class,
                () -> resolver.resolve(PrincipalResolver.PrincipalRequest.ofInternal(ctx)));
        assertEquals("INTERNAL_PRINCIPAL_ID_REQUIRED", e.code());
    }

    /** 显式写 HUMAN 是合法的(数字人代理真人的意图) —— 但那是<em>写的</em>, 不是<em>猜的</em>。 */
    @Test
    void explicitHumanIsHonoured() {
        InvocationContext ctx = new InvocationContext(
                PrincipalType.HUMAN, "user-1", null, "user-1", null, "corr-1");

        ResolvedPrincipal principal = resolver.resolve(PrincipalResolver.PrincipalRequest.ofInternal(ctx));
        assertEquals(PrincipalType.HUMAN, principal.type());
        assertEquals("user-1", principal.principalId());
        assertEquals(ResolvedPrincipal.SOURCE_INTERNAL, principal.source());
    }

    @Test
    void agentKeepsItsCompanionIdAndUserId() {
        InvocationContext ctx = new InvocationContext(
                PrincipalType.AGENT, "companion-1", "companion-1", "user-1", "session-1", "corr-1");

        ResolvedPrincipal principal = resolver.resolve(PrincipalResolver.PrincipalRequest.ofInternal(ctx));
        assertEquals(PrincipalType.AGENT, principal.type());
        assertEquals("companion-1", principal.companionId());
        assertEquals("user-1", principal.userId());
        assertEquals("session-1", principal.sessionId());
        // 网关用 correlationId 派生进程内幂等键, 丢了它同一次因果事件会被执行两次
        assertEquals("corr-1", principal.correlationId());
    }

    /** 无头维护任务的便捷构造 —— 类型仍然是显式写下的 SYSTEM。 */
    @Test
    void systemHelperIsExplicitlySystem() {
        InvocationContext ctx = InternalPrincipalResolver.system("reaper", "corr-9");

        assertEquals(PrincipalType.SYSTEM, ctx.principalType());
        assertEquals("system:reaper", ctx.principalId());
        assertNotNull(resolver.resolve(PrincipalResolver.PrincipalRequest.ofInternal(ctx)));
    }

    @Test
    void supportsOnlyInternalRequests() {
        assertTrue(resolver.supports(PrincipalResolver.PrincipalRequest.ofInternal(
                InternalPrincipalResolver.system("reaper", "c"))));
        assertFalse(resolver.supports(PrincipalResolver.PrincipalRequest.ofHeader("Bearer x", "c")));
        assertFalse(resolver.supports(PrincipalResolver.PrincipalRequest.ofMcp("AGENT:1", "key", "c")));
        assertFalse(resolver.supports(null));
    }
}
