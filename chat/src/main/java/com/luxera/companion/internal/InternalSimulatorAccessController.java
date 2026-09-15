package com.luxera.companion.internal;

import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * chat 平台对仿真 Agent 平台开放的服务间端点 —— {@code SimulatorAccessPort} 的
 * HTTP 面。数字人拿自己"手机"的短期访问令牌: 设备 (deviceId, secret) 是 chat 在
 * provisioning 时交给数字人的, 签发/吊销/TTL 全是 chat 的业务, 对面只见到 token 串。
 *
 * <p>404 = 设备未知/吊销/secret 不符 —— 仓 2 适配器把 404 翻成
 * {@code Optional.empty()}, 连接器对空答案的既有处理就是"不连"。
 */
@RestController
@RequestMapping("/internal/simulator")
public class InternalSimulatorAccessController {

    private final SimulatorAccessPort simulatorAccess;

    public InternalSimulatorAccessController(SimulatorAccessPort simulatorAccess) {
        this.simulatorAccess = simulatorAccess;
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<Map<String, String>> refreshToken(@RequestBody RefreshTokenBody body) {
        Optional<String> token = simulatorAccess.refreshToken(body.deviceId(), body.secret());
        return token.map(t -> ResponseEntity.ok(Map.of("token", t)))
                .orElseGet(() -> ResponseEntity.status(404).build());
    }

    public record RefreshTokenBody(String deviceId, String secret) {}
}
