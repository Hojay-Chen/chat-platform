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
        return provisionSimulatorAccount(displayName, null);
    }

    /**
     * 同上, 但带一个**幂等锚点** —— 一键创建({@code AgentFriendProvisioningService})走的那个。
     *
     * <h2>为什么幂等要做在这一层, 而不是让调用方先查一遍</h2>
     *
     * 因为"先查再建"在调用方那一侧是**两次独立的 HTTP/事务**, 中间隔着一次网络往返:
     * 用户双击、或前端超时重发, 两个请求都会查到"没有", 然后各自建一份。锚点必须落在
     * 唯一索引约束着的那张表上, 判断与写入才在同一个事务里。调用方只需要保证
     * **同一个意图复用同一个 requestId**(它是调用方生成的, 与 {@code client_message_id} 同一先例)。
     *
     * <h2>命中之后的四种形状, 四种答复</h2>
     *
     * <ul>
     *   <li><b>PAIRING 且码未过期</b> —— 原样返回同一个账号、同一台设备、<b>同一个码</b>。
     *       重试的语义是"我没收到上次的答复", 而不是"给我换个码"; 换了码的话, 上一次
     *       那个可能已经在对面手上, 两个码指向同一个账号是没必要的混乱。</li>
     *   <li><b>PAIRING 但码已过期</b> —— 同账号换新码。这里<b>不</b>新建账号: 账号ID
     *       一旦发出去就不可回收({@code Person} 里已有这条不变量), 而重试者手上可能
     *       还捏着那个 id。</li>
     *   <li><b>ACTIVE</b> —— 已经配对过了, 没有码可给(null)。调用方据此知道"这台设备
     *       不需要再配一次", 而不是把 null 当失败。</li>
     *   <li><b>REVOKED</b> —— <b>复活同一账号</b>: 状态回 PAIRING、换新码。这是"上次跑到
     *       第三步失败了, 补偿把设备吊销了, 用户又点了一次"的那条路。用同一个账号是必须的
     *       —— 仓 2 那边的 {@code companions.chat_account_id} 唯一索引记得它, 换个账号就
     *       会绕过那个唯一键、铸出第二份。</li>
     * </ul>
     *
     * <p>四条路都<b>只碰同一行</b>: 无论如何都不会出现第二个 {@code users} 行。
     */
    @Transactional
    public ProvisionResult provisionSimulatorAccount(String displayName, String requestId) {
        String anchor = requestId == null || requestId.isBlank() ? null : requestId.trim();
        if (anchor != null) {
            SimulatorDevice existing = deviceRepo.findByRequestId(anchor).orElse(null);
            if (existing != null) {
                return reuseExisting(existing, displayName);
            }
        }

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
        device.setRequestId(anchor);
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
     * 重试命中已有设备时的四条分支 —— 见 {@link #provisionSimulatorAccount(String, String)} 的表格。
     *
     * <p>单独一个方法, 是因为"重试"这件事有四种形状而每一种都要单独能读出来; 摊平进上面那段
     * 主流程里, 四条分支会看起来像四个 if 特例, 而不是一个完整的边界。
     */
    private ProvisionResult reuseExisting(SimulatorDevice device, String displayName) {
        if (STATUS_ACTIVE.equals(device.getStatus())) {
            // 已配对: 没有码可给, 但**不是失败** —— 调用方接着去做第三步(那边按
            // chat_account_id 幂等, 会拿回同一个 agent)。
            log.info("[Simulator配对] requestId 命中已激活设备 {}, 原样复用", device.getDeviceId());
            return new ProvisionResult(device.getAccountId(), device.getDeviceId(), null, null, null);
        }

        boolean revived = false;
        if (STATUS_REVOKED.equals(device.getStatus())) {
            // 复活同一账号: 令牌版本递增使上一次可能发出去的 JWT 全部失效, secret 一并清掉
            device.setStatus(STATUS_PAIRING);
            device.setTokenVersion(device.getTokenVersion() + 1);
            device.setSecretHash(null);
            revived = true;
            log.info("[Simulator配对] requestId 命中已吊销设备 {}, 复活同一账号 {}",
                    device.getDeviceId(), device.getAccountId());
        } else {
            log.info("[Simulator配对] requestId 命中设备 {}, 复用同一账号 {}", device.getDeviceId(), device.getAccountId());
        }

        // 码: PAIRING 且还有效就原样给(重试的语义是"我没收到上次的答复", 不是"换个码"),
        // 过期了就换一个。
        //
        // 但**刚复活的那台必须换码, 哪怕旧码还没过期** —— 这是吊销这件事的意义所在:
        // 上一次失败时我们正是为了让那个码失效才吊销的设备, 拿同一个码复活等于把刚收掉的
        // 凭据原样发回去。旧码也可能已经在别人手上(失败响应丢失的那种重试, 前提就是
        // 上一次的响应可能已经到达)。
        boolean expired = revived
                || device.getPairingCode() == null
                || device.getPairingCodeExpiresAt() == null
                || device.getPairingCodeExpiresAt().isBefore(LocalDateTime.now());
        if (expired) {
            device.setPairingCode(generatePairingCode());
            device.setPairingCodeExpiresAt(LocalDateTime.now().plusMinutes(pairingTtlMinutes));
        }
        if (displayName != null && !displayName.isBlank()) {
            device.setDisplayName(displayName);
        }
        deviceRepo.save(device);
        return new ProvisionResult(device.getAccountId(), device.getDeviceId(), null,
                device.getPairingCode(), device.getPairingCodeExpiresAt());
    }

    /**
     * 第四步: 把设备绑到对面那个 agent 上 —— 一键创建成功之后收尾。
     *
     * <p>这一步失败要**抛**。它和外面那次跨平台调用不同: 那里失败是我们的编排没走完,
     * 这里失败是"聊天账号与 agent 都建好了, 只是没记下它们是一对", 静默吞掉的话调用方
     * 会报告创建成功, 而用户之后会发现这个 agent 永远说不了话(它不知道自己该用哪个账号)。
     * 抛出去之后重试是安全的 —— 同一个 requestId 会命中同一台设备与同一个 agent, 再走一遍本方法。
     *
     * @throws IllegalArgumentException 设备不存在(不该发生: 设备是本方法调用方刚建的)
     */
    @Transactional
    public void attachCompanion(String deviceId, String companionId) {
        SimulatorDevice device = deviceRepo.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));
        device.setCompanionId(companionId);
        deviceRepo.save(device);
        log.info("[Simulator配对] 设备 {} 已绑定 agent {}", deviceId, companionId);
    }

    /**
     * 把展示名改掉 —— 设备与它那个 SIMULATOR 账号一起改。
     *
     * <h2>为什么需要"改名"这一步</h2>
     *
     * 因为一键创建时**账号先于 agent 存在**, 而 agent 的名字要到对面编译完人格才知道。
     * 先建账号就必然先有一个猜的名字(或者干脆没有), 而这个猜测在几秒后就被证伪了 ——
     * 于是要么当场改正, 要么永远留着一个假名字。
     *
     * <p>两处一起改而不是只改一处: {@code users.nickname}/{@code display_name} 是这个账号
     * 在聊天侧的名字, {@code simulator_devices.display_name} 是设备管理界面上那一行。
     * 只改一处的话, 同一个人在两个界面上有两个名字 —— 而这两个界面都会被同一个用户看到。
     *
     * <p>不可空的名字不写: 空串和不写是两件事, 前者会把已有的名字擦成一个空格。
     */
    @Transactional
    public void renameAccount(String deviceId, String displayName) {
        if (displayName == null || displayName.isBlank()) return;
        SimulatorDevice device = deviceRepo.findById(deviceId).orElse(null);
        if (device == null) return;
        device.setDisplayName(displayName);
        deviceRepo.save(device);
        userRepo.findById(device.getAccountId()).ifPresent(u -> {
            u.setNickname(displayName);
            u.setDisplayName(displayName);
            userRepo.save(u);
        });
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
