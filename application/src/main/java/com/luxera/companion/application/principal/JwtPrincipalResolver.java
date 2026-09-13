package com.luxera.companion.application.principal;

import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.spi.PrincipalTokenReader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * LAP v1: REST 来源的身份 —— 令牌里的 {@code ptype} 说了算。
 *
 * <p>令牌的校验不在这里: {@link PrincipalTokenReader} 由 {@code platform-kernel} 实现(密钥、
 * 算法、过期规则只在那一个地方)。本类只负责把"令牌里的身份"翻译成 LAP 的
 * {@link ResolvedPrincipal}, 以及决定缺失信息该怎么解释。
 *
 * <p>{@link PrincipalType#HUMAN} 的回落只发生在<em>令牌里没有 {@code ptype} claim</em> 时,
 * 也就是在这次重构之前签发的令牌。这是一个有时间边界的兼容措施, 不是"取不到就默认真人"的
 * 通用兜底: 有 ptype 而值不认识, 同样回落 HUMAN —— 那个令牌的来源已经不可信, 让它以最低
 * 权限身份进来比让它 500 更安全。
 */
@Component
public class JwtPrincipalResolver implements PrincipalResolver {

    private static final String BEARER = "Bearer ";

    private final PrincipalTokenReader tokens;

    public JwtPrincipalResolver(PrincipalTokenReader tokens) {
        this.tokens = tokens;
    }

    @Override
    public String source() {
        return ResolvedPrincipal.SOURCE_JWT;
    }

    @Override
    public boolean supports(PrincipalRequest request) {
        return request != null && StringUtils.hasText(request.authorizationHeader())
                && request.authorizationHeader().startsWith(BEARER);
    }

    @Override
    public ResolvedPrincipal resolve(PrincipalRequest request) {
        String token = request.authorizationHeader().substring(BEARER.length()).trim();
        Optional<PrincipalTokenReader.Identity> identity = tokens.read(token);
        if (identity.isEmpty()) {
            throw new PrincipalException("INVALID_TOKEN", "令牌无效或已过期");
        }
        String principalId = identity.get().principalId();
        PrincipalType type = identity.get().principalType() == null
                ? PrincipalType.HUMAN
                : identity.get().principalType();
        // 真人自己就是 userId; Agent 的令牌主体是 companionId, 于是 userId 只能等上层按
        // companion 反查 —— 平台不替它猜, 猜错会让"Agent 代表谁"变成随机值。
        return new ResolvedPrincipal(type, principalId,
                type == PrincipalType.AGENT ? principalId : null,
                type == PrincipalType.HUMAN ? principalId : null,
                null, request.correlationId(), ResolvedPrincipal.SOURCE_JWT);
    }
}
