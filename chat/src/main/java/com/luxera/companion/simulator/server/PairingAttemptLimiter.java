package com.luxera.companion.simulator.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公开配对端点({@code POST /api/simulator/pair})的失败次数限流 —— 按 IP 的滑动窗口。
 *
 * <h2>先说实话: 限流在这里不是主要防线</h2>
 *
 * 主要防线是码本身的性质: 6 位、字母表 32 个({@code 32^6 ≈ 1.07e9})、TTL 10 分钟、
 * 且只在设备的 {@code status = PAIRING} 期间有效。要在窗口内有一半把握猜中, 需要每秒上百万次
 * 猜测 —— 一个每请求都要查一次库的 HTTP 端点在物理上给不出这个吞吐。所以穷举在这条路上
 * 本来就是不可行的。
 *
 * <p>那这个类在防什么? 防三件真会发生的事:
 * <ol>
 *   <li><b>扫描器与噪音</b> —— 一个被公网扫到的端点会被反复试, 每次失败都在库里查一遍、
 *       在日志里留一行。没有闸门的话, 日志里真实的安全事件会被淹掉。</li>
 *   <li><b>参数被改小</b> —— 以后有人把码改回 4 位、或者把 TTL 改成一小时, 上面那段
 *       算术就整个不成立了。限流是那个改动落地时唯一还会拦一下的东西。</li>
 *   <li><b>失败要留痕</b> —— 一个 IP 连续试错是"有人在猜"的唯一信号, 而不限流就没有这个信号。</li>
 * </ol>
 *
 * <h2>为什么按 IP 而不是全局一个计数器</h2>
 *
 * 全局计数器的形状是错的: 一个攻击者打满它, 所有人在这段时间里都配不了对 —— 而配对失败
 * 是用户看得见的("我的新好友连不上"), 攻击者不需要猜中码, 只要打满计数就能造成这个效果。
 * 按 IP 分桶之后, 攻击者只堵住自己。
 *
 * <p>代价是攻击者可以换 IP。这一点由上面的算术兜底 —— 这个类从来不是唯一的防线。
 *
 * <h2>桶数有上限, 溢出的走同一个"公共桶"</h2>
 *
 * 桶的键来自请求头(X-Forwarded-For), 也就是**调用方可以随便编**。不限量地建桶, 就是一个
 * 内存耗尽的入口。所以超过 {@link #MAX_TRACKED} 之后, 新 IP 的失败记在同一个公共桶里 ——
 * 于是"从几千个不同 IP 同时来"这件事本身会被限住, 而不是被放行。这是刻意选的方向:
 * 分布式洪水期间让配对失败(再等 5 分钟), 好过让它畅通无阻。
 */
@Component
public class PairingAttemptLimiter {

    /** 一个 IP 在一个窗口里能失败几次。成功一次即清零。 */
    private static final int MAX_FAILURES = 10;
    /** 窗口长度(秒)。比码的 10 分钟 TTL 短: 猜错的人等得起, 而攻击者拿不到一个长窗口。 */
    private static final long WINDOW_SECONDS = 300;
    /** 最多记多少个 IP 的桶。 */
    private static final int MAX_TRACKED = 4096;
    /** 桶满之后所有新 IP 共用的键。 */
    private static final String OVERFLOW = "*overflow*";

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    private final int maxFailures;
    private final long windowMillis;

    public PairingAttemptLimiter(@Value("${app.simulator.pairing-max-failures-per-ip:10}") int maxFailures,
                                 @Value("${app.simulator.pairing-failure-window-seconds:300}")
                                 long windowSeconds) {
        this.maxFailures = maxFailures <= 0 ? MAX_FAILURES : maxFailures;
        this.windowMillis = (windowSeconds <= 0 ? WINDOW_SECONDS : windowSeconds) * 1000L;
    }

    /**
     * 现在允许这个 IP 再试一次吗? 只看不记 —— 失败要由调用方在<b>确认失败之后</b>调
     * {@link #recordFailure}, 成功则调 {@link #clear}。
     *
     * <p>"先判后记"而不是"进来就记": 猜对的那一次不该被算成失败, 否则一个正常调用的
     * 客户端反复重试同一次配对时, 会把自己顶到墙上。
     */
    public boolean allow(String ip) {
        Deque<Long> bucket = bucketFor(ip, false);
        if (bucket == null) return true;
        synchronized (bucket) {
            prune(bucket, System.currentTimeMillis());
            return bucket.size() < maxFailures;
        }
    }

    public void recordFailure(String ip) {
        Deque<Long> bucket = bucketFor(ip, true);
        if (bucket == null) return;
        long now = System.currentTimeMillis();
        synchronized (bucket) {
            prune(bucket, now);
            bucket.addLast(now);
        }
    }

    /** 配对成功 —— 把这个 IP 的记录整体丢掉。成功的人不该被自己之前的手滑惩罚。 */
    public void clear(String ip) {
        failures.remove(ip);
    }

    /** 还要等多久(秒)才能再试。给 429 的 {@code Retry-After} 用。 */
    public long retryAfterSeconds(String ip) {
        Deque<Long> bucket = failures.get(ip);
        if (bucket == null) return 0;
        synchronized (bucket) {
            Long oldest = bucket.peekFirst();
            if (oldest == null) return 0;
            long wait = (oldest + windowMillis - System.currentTimeMillis() + 999) / 1000;
            return Math.max(1, wait);
        }
    }

    private Deque<Long> bucketFor(String ip, boolean create) {
        String key = ip == null || ip.isBlank() ? OVERFLOW : ip;
        Deque<Long> existing = failures.get(key);
        if (existing != null) return existing;
        if (!create) return null;
        if (failures.size() >= MAX_TRACKED && !OVERFLOW.equals(key)) {
            // 桶满了: 先做一次全局清理(过期桶就是空桶), 清完还满就落到公共桶
            failures.forEach((k, v) -> {
                synchronized (v) {
                    prune(v, System.currentTimeMillis());
                }
                if (v.isEmpty()) failures.remove(k, v);
            });
            if (failures.size() >= MAX_TRACKED) key = OVERFLOW;
        }
        return failures.computeIfAbsent(key, k -> new ArrayDeque<>());
    }

    /** 丢掉窗口之外的时间戳。只看队首 —— 队列是按时间推进的, 队首出窗就出队。 */
    private void prune(Deque<Long> bucket, long now) {
        long cutoff = now - windowMillis;
        for (Iterator<Long> it = bucket.iterator(); it.hasNext(); ) {
            if (it.next() >= cutoff) break;
            it.remove();
        }
    }
}
