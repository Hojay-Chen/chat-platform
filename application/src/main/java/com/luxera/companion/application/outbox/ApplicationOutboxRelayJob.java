package com.luxera.companion.application.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LAP v1: 收件箱的定时投递 —— {@code INBOX} 订阅从"记录"变成"真的会送达"的那一步。
 *
 * <p><b>它补的是直投路径的第二个洞。</b> 直投的下游失败只写一条 WARN 就结束了: 没有重试、
 * 没有终态、没有痕迹。这里每 5 秒扫一次 PENDING, 失败的留在原地等下一轮, 试满才 DEAD。
 *
 * <p>频率比 {@code ActionInvocationReaperJob}(5 分钟)高得多, 因为两件事的时延要求相反:
 * 一条投不出去的<em>事件</em>是用户能感知到的"数字人怎么不理我", 而一条卡住的
 * <em>调用记录</em>只有排障的人会去看。默认每 5 秒一轮, 由
 * {@code app.scheduler.lap-outbox-relay-cron} 配置 —— 生产上要调的是它, 不是代码。
 */
@Slf4j
@Component
public class ApplicationOutboxRelayJob {

    private final OutboxRelay relay;

    public ApplicationOutboxRelayJob(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(cron = "${app.scheduler.lap-outbox-relay-cron:*/5 * * * * *}")
    public void relay() {
        try {
            relay.deliverPending();
        } catch (Exception e) {
            // 与其它维护任务同一条纪律: 一轮失败不该让调度停摆。
            log.error("[OutboxRelay] 投递轮次失败: {}", e.getMessage(), e);
        }
    }
}
