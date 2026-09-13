package com.luxera.companion.application;

import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.spi.PrincipalTokenReader;

import java.util.Optional;

/**
 * 模块测试里的令牌读取器 —— 代替 {@code platform-kernel} 的那个实现。
 *
 * <p>本模块的测试上下文里没有 kernel, 所以这个端口必须被塞一个替身, 否则
 * {@code JwtPrincipalResolver} 建不起来。这个替身刻意做成<em>可用的</em>而不是永远返回空:
 * 应用平台里"真人开的局, Agent 来应手"是最重要的一条路径, 而它恰好要求测试能造出两种
 * 身份的令牌。返回空的替身会让所有跨 principal 的用例只能靠直接构造
 * {@code ResolvedPrincipal} 绕过去, 于是那条路径永远测不到。
 *
 * <p>令牌格式: {@code stub.<HUMAN|AGENT|SYSTEM|APPLICATION>.<principalId>}。
 * 不解析、不签名、不校验 —— 这些是 kernel 的事, 在这里假装有只会让测试以为它验过了。
 */
public class StubPrincipalTokenReader implements PrincipalTokenReader {

    @Override
    public Optional<Identity> read(String token) {
        if (token == null || !token.startsWith("stub.")) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", 3);
        if (parts.length < 3 || parts[2].isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Identity(parts[2], PrincipalType.valueOf(parts[1].toUpperCase())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 测试里造令牌用。 */
    public static String token(PrincipalType type, String principalId) {
        return "stub." + type.name() + "." + principalId;
    }

    public static String bearer(PrincipalType type, String principalId) {
        return "Bearer " + token(type, principalId);
    }
}
