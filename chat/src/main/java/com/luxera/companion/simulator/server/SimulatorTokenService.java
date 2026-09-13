package com.luxera.companion.simulator.server;

import com.luxera.companion.contracts.dhcp.DhcpConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * V10 §25/§28 Simulator 访问令牌: 短期 JWT。
 *
 * - sub       = deviceId
 * - accountId = 拥有者账号
 * - scopes    = 授权能力(逗号分隔)
 * - tv        = tokenVersion(吊销即失效: 校验时与 DB 对比, 不匹配则拒绝)
 * - exp       = now + TTL(默认 300s, DhcpConstants)
 *
 * 密钥与平台用户 JWT 分开(app.simulator.token-secret), 避免令牌混用。
 * 密钥仅用于 HMAC; 不落库, 不通过 API 泄露。
 */
@Slf4j
@Service
public class SimulatorTokenService {

    private final String secret;
    private final long ttlSeconds;
    private Key signingKey;

    public SimulatorTokenService(
            @Value("${app.simulator.token-secret:luxera-simulator-dhcp-v1-dev-secret-change-me}") String secret,
            @Value("${app.simulator.token-ttl-seconds:" + DhcpConstants.DEFAULT_TOKEN_TTL_SECONDS + "}") long ttlSeconds) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    @PostConstruct
    void init() {
        // 至少 32 字节(256bit)满足 HS256
        String s = secret.length() >= 32 ? secret : (secret + "0123456789abcdef0123456789abcdef").substring(0, 32);
        this.signingKey = Keys.hmacShaKeyFor(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发短期访问令牌 */
    public String issue(String deviceId, String accountId, Set<String> scopes, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(deviceId)
                .claim("accountId", accountId)
                .claim("scopes", String.join(",", scopes))
                .claim("tv", tokenVersion)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 校验并解析令牌。返回 null 表示无效/过期(调用方应回 AUTH_FAILED)。
     * 注意: tokenVersion 的最终校验需要与 DB 对比(设备可能已被吊销/轮换), 由
     * SimulatorAuthHandler 完成; 本方法只做签名与格式校验。
     */
    public AuthClaims verify(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String deviceId = claims.getSubject();
            String accountId = claims.get("accountId", String.class);
            String scopesRaw = claims.get("scopes", String.class);
            Integer tv = claims.get("tv", Integer.class);
            if (deviceId == null || accountId == null) return null;
            Set<String> scopes = scopesRaw == null || scopesRaw.isBlank()
                    ? Set.of()
                    : Set.of(scopesRaw.split(","));
            return new AuthClaims(deviceId, accountId, scopes, tv == null ? 0 : tv,
                    claims.getExpiration().toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public record AuthClaims(String deviceId, String accountId, Set<String> scopes,
                             int tokenVersion, Instant expiresAt) {}
}
