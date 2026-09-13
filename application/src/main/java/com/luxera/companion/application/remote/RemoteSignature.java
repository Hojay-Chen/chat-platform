package com.luxera.companion.application.remote;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * LAP v1: REMOTE 应用调用的签名 —— <b>平台对远端说"这条消息确实是我发的, 且没被改过"。</b>
 *
 * <p>签的是 {@code <timestamp>.<body>} 而不是只签 body: 只签 body 的话, 一个能读到历史请求的
 * 人可以把整条消息原样重放到明天, 而签名依然合法。把时间戳纳入签名, 远端就能用
 * {@code |now - timestamp| < 窗口} 把重放挡掉 —— <b>平台这边只负责让这件事成为可能</b>,
 * 窗口多宽由远端决定(它的时钟偏差、它的容忍度, 只有它知道)。
 *
 * <p>用 HMAC-SHA256 而不是裸 SHA256: 后者任何人拿到 body 就能算, 等于没有密钥。
 *
 * <p>比较用 {@link MessageDigest#isEqual} —— 一个字节一个字节比到不一样为止的实现, 会把
 * "前几位对了"这件事通过耗时泄漏出去。在签名校验这种地方, 这种泄漏是能被利用的。
 */
public final class RemoteSignature {

    /** 密钥来自平台配置({@code manifest.runtime.remote.authRef} 指向的名字), 永远不来自 manifest。 */
    public static final String HEADER_SIGNATURE = "X-Lap-Signature";
    public static final String HEADER_TIMESTAMP = "X-Lap-Timestamp";
    public static final String SCHEME = "sha256=";

    private RemoteSignature() {
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return SCHEME + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用: " + e.getMessage(), e);
        }
    }

    /** 远端(以及本平台的测试)用它校验。常量时间比较。 */
    public static boolean verify(String secret, String timestamp, String body, String header) {
        if (secret == null || header == null || timestamp == null) {
            return false;
        }
        String expected = sign(secret, timestamp, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8));
    }
}
