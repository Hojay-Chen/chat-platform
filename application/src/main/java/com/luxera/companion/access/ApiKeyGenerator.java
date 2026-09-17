package com.luxera.companion.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 第三方接入钥匙的生成与哈希 —— 与仓 2 的 {@code ApiKeyGenerator} 同一套做法。
 *
 * <p>前缀 {@code cak_}(chat access key) 是<b>给人看的</b>: 一把钥匙出现在日志、工单或
 * 截图里时, 一眼能认出它是聊天平台的接入钥匙而不是 8092 的 {@code sap_} 服务钥匙 ——
 * 两把钥匙长得一样、用途完全不同, 混起来的那次一定发生在"贴错了"而不是"算错了"。
 */
public final class ApiKeyGenerator {

    /** 明文前缀。校验侧也用它做初筛, 免得把明显不是钥匙的东西也算一遍哈希。 */
    public static final String KEY_PREFIX = "cak_";

    /**
     * 明文里随机部分的字符数。32 个 32 进制字符 = 160 bit —— 穷举不可行,
     * 而这个长度贴进配置文件、环境变量都不至于让人想去换行。
     */
    private static final int RANDOM_CHARS = 32;
    /** 与 {@code SimulatorPairingService} 同一套字母表: 去掉易混的 I/O/0/1。 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    /**
     * 一次签发的结果。
     *
     * <p>{@code plaintext} 与另外两项分开装在一个 record 里, 是为了让调用方<b>没法只拿到哈希</b>
     * —— 那样"签发"就变成了"发了一把谁也不知道是什么的钥匙"。明文只在这一处存在,
     * 落库的是 {@code hash}。
     */
    public record GeneratedKey(String plaintext, String hash, String prefix) {
    }

    public static GeneratedKey generate() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX.length() + RANDOM_CHARS);
        sb.append(KEY_PREFIX);
        for (int i = 0; i < RANDOM_CHARS; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String plaintext = sb.toString();
        // 列表里给人看的片段: 前缀 + 前 4 位随机字符。够认人, 不够重建。
        return new GeneratedKey(plaintext, sha256Hex(plaintext),
                KEY_PREFIX + plaintext.substring(KEY_PREFIX.length(), KEY_PREFIX.length() + 4));
    }

    /**
     * 校验侧的哈希。与 {@link #generate()} 用的是同一个函数 —— 两个方向只要有一处不同,
     * 症状是"刚发的钥匙立刻就 401", 而两边看着都对。
     */
    public static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用: " + e.getMessage(), e);
        }
    }
}
