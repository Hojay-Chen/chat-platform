package com.luxera.companion.config;

import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.spi.PrincipalTokenReader;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * LAP v1: 把 {@link JwtUtil} 接到 {@link PrincipalTokenReader} 端口上。
 *
 * <p><b>为什么这个类存在。</b>{@code application-platform} 需要"这个令牌是谁", 但它不该依赖
 * {@code platform-kernel} —— 那会把用户表、Spring Security 过滤链、全局异常处理一起拖进应用
 * 平台的 classpath, 连带着让那个模块的独立测试上下文再也起不来。所以 {@code contracts} 里放了
 * 一个只回答这一个问题的端口, 实现留在唯一拥有密钥与算法的地方。
 *
 * <p><b>对既有行为零影响</b>: 这是一个新增的 Bean, 不改 {@link JwtUtil} 的任何语义, 也不被任何
 * 既有代码使用。改 {@code JwtUtil} 的只有 {@code ptype} 那一个 claim, 那是本次重构唯一一处
 * 触及 kernel 的<em>行为</em>改动。
 *
 * <p>三次 {@code parseClaims} 是有意的: {@link JwtUtil} 现有的公开方法各自独立解析, 为了取
 * 一次身份去改它的签名不值得 —— HMAC 校验是微秒级的, 而"多解析两次"和"给一个被 294 个测试
 * 依赖的类加方法"不是一个量级的风险。
 */
@Component
public class JwtPrincipalTokenReader implements PrincipalTokenReader {

    private final JwtUtil jwtUtil;

    public JwtPrincipalTokenReader(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Optional<Identity> read(String token) {
        if (token == null || token.isBlank() || !jwtUtil.isValid(token)) {
            return Optional.empty();
        }
        String subject = jwtUtil.getUserId(token);
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        PrincipalType type = jwtUtil.getPrincipalType(token);
        return Optional.of(new Identity(subject, type));
    }
}
