package com.luxera.companion.simulator.server;

import com.luxera.chatframework.ChatPlatformTestApplication;
import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §26/§65 Simulator 配对生命周期测试:
 * - provision: 普通账号(user_kind=SIMULATOR) + 设备行(PAIRING)
 * - completePairing: 码换一次性 secret + ACTIVE
 * - refreshBySecret: secret → 新 token(旧 token 自然过期)
 * - revoke: tokenVersion 递增使已发 JWT 失效
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class SimulatorPairingServiceTest {

    @Autowired
    SimulatorPairingService pairingService;
    @Autowired
    SimulatorTokenService tokenService;
    @Autowired
    UserRepository userRepo;

    @Test
    void provisionCreatesSimulatorAccountAndPairingDevice() {
        SimulatorPairingService.ProvisionResult r = pairingService.provisionSimulatorAccount("小柔的手机");

        assertNotNull(r.accountId());
        assertNotNull(r.deviceId());
        assertNotNull(r.pairingCode());
        assertEquals(6, r.pairingCode().length());

        // 账号是普通用户行 + user_kind=SIMULATOR(V10 §30 无 BotUser)
        User u = userRepo.findById(r.accountId()).orElseThrow();
        assertEquals("SIMULATOR", u.getUserKind());
        assertEquals("小柔的手机", u.getDisplayName());
    }

    @Test
    void completePairingActivatesDeviceAndReturnsOneShotSecret() throws InterruptedException {
        var r = pairingService.provisionSimulatorAccount("测试设备A");
        var done = pairingService.completePairing(r.pairingCode());

        assertNotNull(done.secret());
        assertEquals(40, done.secret().length());
        assertNotNull(done.accessToken());

        // 同秒签发的两个 JWT 内容相同(iat 是秒精度) — 加 1.1s 偏移以确保 tokenVersion 或时间不同
        Thread.sleep(1100);
        String refreshed = pairingService.refreshBySecret(done.deviceId(), done.secret());
        assertNotNull(refreshed);

        // 设备已 ACTIVE
        Optional<SimulatorDevice> dev = pairingService.getDeviceIfActive(done.deviceId());
        assertTrue(dev.isPresent());
        assertNull(dev.get().getPairingCode()); // 一次性码已清
    }

    @Test
    void completePairingRejectsExpiredOrWrongCode() {
        assertThrows(IllegalArgumentException.class,
                () -> pairingService.completePairing("WRONG1"));
        assertThrows(IllegalArgumentException.class,
                () -> pairingService.completePairing(null));
    }

    @Test
    void refreshRejectsBadSecretOrUnknownDevice() {
        var r = pairingService.provisionSimulatorAccount("测试设备B");
        var done = pairingService.completePairing(r.pairingCode());

        assertThrows(IllegalArgumentException.class,
                () -> pairingService.refreshBySecret(done.deviceId(), "BAD-SECRET-XXX"));
        assertThrows(IllegalArgumentException.class,
                () -> pairingService.refreshBySecret("no-such-device", done.secret()));
    }

    @Test
    void revokeInvalidatesOldTokens() throws InterruptedException {
        var r = pairingService.provisionSimulatorAccount("测试设备C");
        var done = pairingService.completePairing(r.pairingCode());

        // 吊销前 token 有效
        SimulatorTokenService.AuthClaims before = tokenService.verify(done.accessToken());
        assertNotNull(before);

        pairingService.revokeDevice(done.deviceId());

        // 吊销后: 旧 token 签名仍验证通过(纯 JWT), 但 tokenVersion 已与 DB 不符 → AUTH 拒绝
        SimulatorTokenService.AuthClaims after = tokenService.verify(done.accessToken());
        assertNotNull(after);
        Optional<SimulatorDevice> dev = pairingService.getDeviceIfActive(done.deviceId());
        assertTrue(dev.isEmpty()); // 不再是 ACTIVE
        // secret 也作废
        assertThrows(IllegalArgumentException.class,
                () -> pairingService.refreshBySecret(done.deviceId(), done.secret()));
    }

    @Test
    void tokenVerifyRoundTrip() {
        String token = tokenService.issue("dev-x", "acc-y", java.util.Set.of("chat.read", "chat.send"), 3);
        SimulatorTokenService.AuthClaims claims = tokenService.verify(token);
        assertNotNull(claims);
        assertEquals("dev-x", claims.deviceId());
        assertEquals("acc-y", claims.accountId());
        assertEquals(3, claims.tokenVersion());
        assertEquals(java.util.Set.of("chat.read", "chat.send"), claims.scopes());
    }

    @Test
    void tokenVerifyRejectsGarbage() {
        assertNull(tokenService.verify("not-a-token"));
        assertNull(tokenService.verify(""));
        assertNull(tokenService.verify(null));
    }
}
