package com.luxera.companion.application.domain;

import java.util.Set;

/**
 * LAP v1: 一次动作调用的终态。<b>四态, 不是三态</b> —— {@link #EXPIRED} 是刻意留出来的。
 *
 * <pre>
 *   IN_PROGRESS ──► SUCCESS
 *        │      ──► FAILED
 *        │      ──► EXPIRED   (JVM 死在执行中途, 没人回填)
 * </pre>
 *
 * <p>没有 EXPIRED 的话, 一次进程崩溃会让那个幂等键<em>永久</em>卡在 IN_PROGRESS: 重试得到
 * 409, 又永远等不到终态。有了它, {@code ActionInvocationReaperJob} 至少能把话说清楚
 * ("这次执行结果不可知"), 而不是静默丢弃。
 */
public enum InvocationStatus {

    IN_PROGRESS,
    SUCCESS,
    FAILED,
    EXPIRED;

    private static final Set<InvocationStatus> TERMINAL = Set.of(SUCCESS, FAILED, EXPIRED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }
}
