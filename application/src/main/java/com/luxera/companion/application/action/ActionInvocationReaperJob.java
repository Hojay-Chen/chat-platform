package com.luxera.companion.application.action;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LAP v1: 崩溃遗留调用的回收器。
 *
 * <p><b>它解决的问题</b>: 一次动作先提交了 {@code IN_PROGRESS} 行(tx1), 然后 JVM 在执行中途
 * 死掉。没有人会回来回填终态, 于是那个幂等键永远卡在"执行中": 客户端重试得到 409,
 * 而且永远等不到头。没有这个任务, "幂等"就从保护变成了陷阱。
 *
 * <p><b>为什么敢断言"业务未生效"</b>: 业务写入与终态回填写在<em>同一个</em>事务里
 * (见 {@link IdempotencyService} 的类注释)。所以一条还停在 {@code IN_PROGRESS} 的行,
 * 意味着那个事务从未提交 —— 这不是推断, 是事务语义的直接推论。
 *
 * <p><b>与抢占的分工</b>: 正常的崩溃恢复路径是<em>客户端重试</em> —— 超过
 * {@code invocation-timeout}(默认 60s)后重试者会用 CAS 抢占用行并重新执行。回收器用的是
 * 长得多的 {@code reap-after}(默认 15min), 只处理"再也没人重试"的那些。两个阈值合并成一个
 * 的话, 回收器会在客户端还来得及重试之前就把键烧成 {@code EXPIRED}。
 */
@Slf4j
@Component
public class ActionInvocationReaperJob {

    private final IdempotencyService idempotency;

    public ActionInvocationReaperJob(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    @Scheduled(cron = "${app.scheduler.invocation-reaper-cron:0 */5 * * * *}")
    public void reap() {
        try {
            int reaped = idempotency.reapStale();
            if (reaped > 0) {
                log.warn("[InvocationReaper] 回收了 {} 条崩溃遗留的调用(超时 {} 未回填终态)",
                        reaped, idempotency.reapAfter());
            }
        } catch (Exception e) {
            log.error("[InvocationReaper] 回收失败: {}", e.getMessage(), e);
        }
    }
}
