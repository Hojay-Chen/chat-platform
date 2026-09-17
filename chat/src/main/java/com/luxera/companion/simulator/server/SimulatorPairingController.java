package com.luxera.companion.simulator.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开配对端点 —— 需求⑦ 在聊天平台侧的最小落点: <b>把 pairingCode 换成凭据</b>。
 *
 * <h2>它补的是一个真实的缺口, 不是"顺手加的对外接口"</h2>
 *
 * {@code SimulatorPairingService.completePairing} 在此之前<b>零生产调用者</b>:
 * {@code InternalSimulatorAccessController} 只暴露 {@code refresh-token}(secret → 新 token),
 * 而 secret 的唯一来源是 {@code completePairing} 的返回 —— 那条路只有测试在走。
 * 也就是说: 平台会发配对码, 而<b>没有任何一条路能拿它换到东西</b>。三方 agent 程序即使
 * 拿到了码也无处可用, "给其他 agent 程序同等的接入能力"在脚下就断了。
 *
 * <h2>为什么它必须是公开的</h2>
 *
 * 因为调用方此刻<b>什么都还没有</b>: 没有 JWT、没有 secret、没有 API key。它手里唯一的凭据
 * 就是那个码。所以它不能落在 {@code /internal/**}(那是服务间 HMAC 面)、也不能落在
 * {@code /api/companions/**}(那个前缀整段被兜底转发给 8091, 见
 * {@code AgentFriendController} 的类注释)。放在 {@code /api/simulator/} 下与 WS 面
 * ({@code /ws/simulator})同族 —— 它们服务的是同一批调用方、同一套设备凭据。
 *
 * <h2>授权就是"知道那个码"</h2>
 *
 * 码是 6 位随机、TTL 10 分钟、只对 PAIRING 状态的设备有效, 且一次配对成功即失效
 * ({@code completePairing} 里 status 变 ACTIVE、码清空)。所以"猜不中"是靠码空间与短 TTL
 * 保证的, 而 {@link PairingAttemptLimiter} 处理的是另外三件事(扫描噪音、以后有人把码改短、
 * 失败留痕)。它在类注释里把这条分工写清楚了, 免得后来者以为限流是主要防线。
 *
 * <h2>404 而不是 401</h2>
 *
 * 码错、码过期、设备已激活 —— 对外是同一件事: <b>这个码换不到东西</b>。分开答等于告诉
 * 一个正在猜码的人"这个码的形状是对的, 只是过期了" —— 那对正常调用方毫无用处。
 */
@Slf4j
@RestController
@RequestMapping("/api/simulator")
public class SimulatorPairingController {

    private final SimulatorPairingService pairing;
    private final PairingAttemptLimiter limiter;

    public SimulatorPairingController(SimulatorPairingService pairing, PairingAttemptLimiter limiter) {
        this.pairing = pairing;
        this.limiter = limiter;
    }

    @PostMapping("/pair")
    public ResponseEntity<?> pair(@RequestBody(required = false) PairBody body,
                                  HttpServletRequest request) {
        String ip = clientIp(request);
        if (!limiter.allow(ip)) {
            long wait = limiter.retryAfterSeconds(ip);
            log.warn("[Simulator配对] {} 失败次数超限, {} 秒内不再受理", ip, wait);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(wait))
                    .body(Map.of("error", "配对尝试过于频繁", "retryAfterSeconds", wait));
        }

        String code = body == null ? null : body.pairingCode();
        if (code == null || code.isBlank()) {
            limiter.recordFailure(ip);
            return ResponseEntity.badRequest().body(Map.of("error", "pairingCode 不能为空"));
        }

        SimulatorPairingService.PairingResult result;
        try {
            result = pairing.completePairing(code);
        } catch (IllegalArgumentException e) {
            limiter.recordFailure(ip);
            log.warn("[Simulator配对] {} 的配对码 {} 被拒: {}", ip, mask(code), e.getMessage());
            // 具体原因(无效/过期)只进日志, 不进响应 —— 见类注释最后一段。
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "配对码无效"));
        }

        limiter.clear(ip);
        log.info("[Simulator配对] 设备 {} 配对成功(账号 {})", result.deviceId(), result.accountId());

        Map<String, Object> out = new LinkedHashMap<>();
        // deviceId 与 secret 一起回去: 设备侧之后用 (deviceId, secret) 换新 token
        // (InternalSimulatorAccessController.refresh-token), 少给一个那条路就走不通 ——
        // 而它的失败表现是"连上了, 5 分钟后掉线", 离这里很远。
        out.put("deviceId", result.deviceId());
        out.put("accountId", result.accountId());
        out.put("secret", result.secret());
        out.put("accessToken", result.accessToken());
        out.put("tokenTtlSeconds", result.tokenTtlSeconds());
        out.put("hint", "secret 只出现这一次; 之后用 POST /internal/simulator/refresh-token 换新 token");
        return ResponseEntity.ok(out);
    }

    /**
     * 调用方 IP —— 直连方是回环时(生产上是宿主机 nginx)才信 {@code X-Forwarded-For}。
     *
     * <p>无条件信这个头等于让攻击者自己选桶: 每次请求换一个假 IP, 限流就形同不存在。
     * 只在直连方是回环时才信, 是因为那时这个头只能由本机的反代写进来 —— 它写的是真实客户端。
     */
    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null) return null;
        boolean loopback = "127.0.0.1".equals(remote) || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote);
        if (!loopback) return remote;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remote;
        // XFF 是逗号分隔的链, 第一段是原始客户端
        int comma = forwarded.indexOf(',');
        String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        return first.isEmpty() ? remote : first;
    }

    /** 日志里不留完整配对码 —— 它在这 10 分钟里就是一把凭据。 */
    private static String mask(String code) {
        String c = code.trim();
        return c.length() <= 2 ? "**" : c.substring(0, 2) + "****";
    }

    public record PairBody(String pairingCode) {}
}
