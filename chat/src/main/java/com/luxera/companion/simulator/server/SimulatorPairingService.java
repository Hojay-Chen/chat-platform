package com.luxera.companion.simulator.server;

import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * V10 §26-§27 Simulator 配对与设备生命周期(平台侧)。
 *
 * 配对流(与用户登录后的"应用管理"对应, 本过渡实现由 DH 侧 provisioning 调用):
 *   startPairing(accountId)      → 生成 6 位 pairingCode(10min), 设备行 PAIRING
 *   completePairing(code)        → 设备 ACTIVE + 生成一次性 clientSecret(只返回明文一次,
 *                                  DB 只存 bcrypt hash) + 签发首个短期 token
 *
 * 凭据安全(V10 §25/§64):
 * - secret 明文只在 completePairing 返回一次, 之后不可取回
 * - DB 存 secretHash(bcrypt)
 * - 吊销 revokeDevice() 递增 tokenVersion, 所有已发 JWT 失效
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulatorPairingService {

    public static final String STATUS_PAIRING = "PAIRING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    /** 默认 scopes: 数字人手机需要的最小集(消息读写 + 会话列表 + 状态推进) */
    public static final Set<String> DEFAULT_SCOPES = Set.of(
            "chat.read", "chat.send", "conversation.list", "delivery.update");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SECRET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SimulatorDeviceRepository deviceRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final SimulatorTokenService tokenService;

    @Value("${app.simulator.pairing-code-ttl-minutes:10}")
    private int pairingTtlMinutes;

    /**
     * V10 §29: 为数字人铸一个"普通聊天账号 + 设备"。
     * users 行 user_kind='SIMULATOR', 无密码登录(仅设备凭据鉴权) —— 不是 BotUser,
     * 消息/会话/已读链路对该账号与真人账号完全一致。
     */
    @Transactional
    public ProvisionResult provisionSimulatorAccount(String displayName) {
        // 1. 普通用户行(密码为随机值, 不可登录; 走 /ws/simulator 设备鉴权)
        User simUser = new User();
        simUser.setUsername("sim-" + UUID.randomUUID().toString().substring(0, 12));
        String unusablePasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
        simUser.setPasswordHash(unusablePasswordHash);
        simUser.setEmail("sim-" + UUID.randomUUID().toString().substring(0, 8) + "@simulator.local");
        simUser.setNickname(displayName);
        simUser.setDisplayName(displayName);
        simUser.setUserKind("SIMULATOR");
        userRepo.save(simUser);

        // 2. 设备行(PAIRING → complete 后 ACTIVE)
        SimulatorDevice device = new SimulatorDevice();
        device.setAccountId(simUser.getId());
        device.setDisplayName(displayName);
        device.setStatus(STATUS_PAIRING);
        device.setScopes(String.join(",", DEFAULT_SCOPES));
        device.setPairingCode(generatePairingCode());
        device.setPairingCodeExpiresAt(LocalDateTime.now().plusMinutes(pairingTtlMinutes));
        deviceRepo.save(device);

        log.info("[Simulator配对] 已创建账号 {} + 设备 {}({})", simUser.getId(), device.getDeviceId(), displayName);
        return new ProvisionResult(simUser.getId(), device.getDeviceId(), null,
                device.getPairingCode(), device.getPairingCodeExpiresAt());
    }

    /**
     * V10 §26: 用 pairingCode 完成配对 → 设备激活 + 一次性 secret + 首个 token。
     * 失败(码错/过期)抛 IllegalArgumentException。
     */
    @Transactional
    public PairingResult completePairing(String pairingCode) {
        if (pairingCode == null || pairingCode.isBlank()) {
            throw new IllegalArgumentException("配对码不能为空");
        }
        SimulatorDevice device = deviceRepo
                .findByPairingCodeAndStatus(pairingCode.trim().toUpperCase(Locale.ROOT), STATUS_PAIRING)
                .orElseThrow(() -> new IllegalArgumentException("配对码无效"));

        if (device.getPairingCodeExpiresAt() == null
                || device.getPairingCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("配对码已过期");
        }

        // 一次性 secret(明文只在本响应出现; DB 只存 bcrypt)
        String secret = generateSecret();
        device.setSecretHash(passwordEncoder.encode(secret));
        device.setStatus(STATUS_ACTIVE);
        device.setPairingCode(null);
        device.setPairingCodeExpiresAt(null);
        device.setLastSeenAt(LocalDateTime.now());
        deviceRepo.save(device);

        String token = tokenService.issue(device.getDeviceId(), device.getAccountId(),
                Set.of(device.getScopes().split(",")), device.getTokenVersion());
        return new PairingResult(device.getDeviceId(), device.getAccountId(), secret, token,
                tokenService.ttlSeconds());
    }

    /**
     * V10 §65: secret 换新短期 token。DH 侧持 secret 定期刷新(默认 TTL 300s)。
     * 校验: 设备存在 + ACTIVE + secretHash 匹配。
     */
    @Transactional
    public String refreshBySecret(String deviceId, String secret) {
        SimulatorDevice device = deviceRepo.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));
        if (!STATUS_ACTIVE.equals(device.getStatus())) {
            throw new IllegalArgumentException("设备未激活或已吊销");
        }
        if (device.getSecretHash() == null || !passwordEncoder.matches(secret, device.getSecretHash())) {
            throw new IllegalArgumentException("secret 校验失败");
        }
        device.setLastSeenAt(LocalDateTime.now());
        deviceRepo.save(device);
        return tokenService.issue(device.getDeviceId(), device.getAccountId(),
                Set.of(device.getScopes().split(",")), device.getTokenVersion());
    }

    /** V10 §65 吊销: tokenVersion+1 使已发 JWT 全部失效, secret 作废 */
    @Transactional
    public void revokeDevice(String deviceId) {
        deviceRepo.findById(deviceId).ifPresent(d -> {
            d.setStatus(STATUS_REVOKED);
            d.setTokenVersion(d.getTokenVersion() + 1);
            d.setSecretHash(null);
            deviceRepo.save(d);
            log.info("[Simulator配对] 设备 {} 已吊销", deviceId);
        });
    }

    /** 列出设备(管理/诊断) */
    @Transactional(readOnly = true)
    public List<SimulatorDevice> listDevices() {
        return deviceRepo.findAll();
    }

    /** AUTH 校验用: 设备存在且 ACTIVE */
    @Transactional(readOnly = true)
    public Optional<SimulatorDevice> getDeviceIfActive(String deviceId) {
        return deviceRepo.findByDeviceIdAndStatus(deviceId, STATUS_ACTIVE);
    }

    /** AUTH 成功后更新 lastSeen(轻量; 失败可忽略) */
    @Transactional
    public void updateLastSeen(String deviceId) {
        try {
            deviceRepo.findById(deviceId).ifPresent(d -> {
                d.setLastSeenAt(LocalDateTime.now());
                deviceRepo.save(d);
            });
        } catch (Exception ignored) {
        }
    }

    /** 刷新 token 前的 secret 校验共通入口 */
    @Transactional(readOnly = true)
    public Optional<SimulatorDevice> findByDeviceId(String deviceId) {
        return deviceRepo.findById(deviceId);
    }

    private String generatePairingCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(SECRET_ALPHABET.charAt(RANDOM.nextInt(SECRET_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String generateSecret() {
        StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            sb.append(SECRET_ALPHABET.charAt(RANDOM.nextInt(SECRET_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** provisionSimulatorAccount 的结果(含一次性 pairingCode) */
    public record ProvisionResult(String accountId, String deviceId, String secret,
                                   String pairingCode, LocalDateTime pairingCodeExpiresAt) {}

    /** completePairing 的结果(secret 明文 + 首个 token, 均一次性) */
    public record PairingResult(String deviceId, String accountId, String secret, String accessToken,
                                long tokenTtlSeconds) {}
}
