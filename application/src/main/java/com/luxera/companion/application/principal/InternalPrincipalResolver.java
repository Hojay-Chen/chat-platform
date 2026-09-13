package com.luxera.companion.application.principal;

import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LAP v1: DH 进程内来源的身份 —— <b>必须显式声明, 没有默认值。</b>
 *
 * <p>这是三个解析器里唯一一个"我可以更宽松一点"会显得很自然的地方: 调用发生在同一个 JVM,
 * 调用方就是数字人自己, 类型显然该是 AGENT —— 于是写成 {@code ctx.principalType() == null
 * ? AGENT : ctx.principalType()} 看起来无害。
 *
 * <p>它是本轮最危险的一行代码。因为 {@code DefaultActionsRuntime} 时代恰恰是反过来的:
 * 类型默认 HUMAN, Agent 的调用没有带类型, 于是一路以真人的身份通过检查。默认值一旦存在,
 * 就会有人依赖它; 依赖它的人多了, 类型就不再是事实而是一个装饰。
 *
 * <p>所以: {@code principalType} 为空 → <b>抛异常</b>。调用方要么说清楚自己是谁, 要么失败。
 * 显式写了 {@code HUMAN} 是合法的(数字人代理真人的意图), 但那是<em>写的</em>, 不是<em>猜的</em>。
 */
@Component
public class InternalPrincipalResolver implements PrincipalResolver {

    @Override
    public String source() {
        return ResolvedPrincipal.SOURCE_INTERNAL;
    }

    @Override
    public boolean supports(PrincipalRequest request) {
        return request != null && request.internal() != null;
    }

    @Override
    public ResolvedPrincipal resolve(PrincipalRequest request) {
        InvocationContext ctx = request.internal();
        if (ctx.principalType() == null) {
            throw new PrincipalException("INTERNAL_PRINCIPAL_TYPE_REQUIRED",
                    "进程内调用必须显式声明 PrincipalType —— "
                            + "不接受默认值, 因为'默认真人'会让 Agent 绕过所有真人专属检查");
        }
        if (!StringUtils.hasText(ctx.principalId())) {
            throw new PrincipalException("INTERNAL_PRINCIPAL_ID_REQUIRED",
                    "进程内调用必须带 principalId");
        }
        return new ResolvedPrincipal(ctx.principalType(), ctx.principalId(),
                ctx.companionId(), ctx.userId(), ctx.sessionId(),
                ctx.correlationId() != null ? ctx.correlationId() : request.correlationId(),
                ResolvedPrincipal.SOURCE_INTERNAL);
    }

    /** 无头调用(SYSTEM 维护任务)的便捷构造 —— 类型仍然是显式写下的。 */
    public static InvocationContext system(String what, String correlationId) {
        return new InvocationContext(PrincipalType.SYSTEM, "system:" + what, null, null, null, correlationId);
    }
}
