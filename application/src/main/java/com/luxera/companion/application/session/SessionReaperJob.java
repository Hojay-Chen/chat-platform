package com.luxera.companion.application.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LAP v2: <b>空闲会话的回收器。</b>
 *
 * <p>{@code application_session} 是平台级的, 也就是说<em>没人替平台清理它</em> —— 每个应用都
 * 只管自己的业务表(棋局、提醒), 会话这一行对它们而言是透明的。没有这个任务, 每开一局棋就永久
 * 多一行 ACTIVE 会话; 它们不出现在任何界面上, 只是慢慢把查询拖长, 并且让"这个数字人现在在玩
 * 几盘棋"这种问题永远得不到正确答案。
 *
 * <p><b>扫的是 {@code ACTIVE} 与 {@code WAITING} 两种</b>(见
 * {@link ApplicationSessionService#reapIdle}): 一个等了七天还没等到人的会话同样该收掉 ——
 * "没人来"不是它继续占着的地方的理由。{@code PAUSED} 不在其中: 暂停是一个<em>有人做出的</em>
 * 决定, 而回收器不该替人撤销决定; 真要收, 有显式的结束会话那条路。
 *
 * <p><b>回收阈值与{@code ActionInvocationReaperJob}不同, 而且必须不同。</b> 那边是 60 秒的
 * "这次调用还活着吗", 这边是"这个人还在这局里吗" —— 用分钟级去关会话, 会让一局下到一半、
 * 想了三分钟的棋被判死。默认 7 天, 与"一局棋的合理寿命"同量级。
 *
 * <p><b>结束会话不等于删除。</b> 状态置 {@code ENDED}, 行留着: {@code resource} 与
 * {@code action_invocation} 都指向它, 而"这盘棋是谁在什么时候跟谁下的"正是审计要回答的问题。
 * 删掉会话会让历史的调用变成一个指向不存在目标的引用。
 */
@Slf4j
@Component
public class SessionReaperJob {

    private final ApplicationSessionService sessions;
    private final long idleHours;

    public SessionReaperJob(ApplicationSessionService sessions,
                            @Value("${app.lap.session.reap-idle-hours:168}") long idleHours) {
        this.sessions = sessions;
        this.idleHours = idleHours;
    }

    @Scheduled(cron = "${app.scheduler.session-reaper-cron:0 30 4 * * *}")
    public void reap() {
        try {
            int reaped = sessions.reapIdle(idleHours);
            if (reaped > 0) {
                log.info("[SessionReaper] 结束了 {} 个空闲超过 {} 小时的会话", reaped, idleHours);
            }
        } catch (Exception e) {
            // 一次回收失败不该让调度停摆: 下一轮还会扫到同一批(它们仍是 ACTIVE)。
            log.warn("[SessionReaper] 回收失败: {}", e.getMessage(), e);
        }
    }

    /** 测试与运维问"阈值是多少"的地方 —— 免得那个数字只活在注解里。 */
    public long idleHours() {
        return idleHours;
    }
}
