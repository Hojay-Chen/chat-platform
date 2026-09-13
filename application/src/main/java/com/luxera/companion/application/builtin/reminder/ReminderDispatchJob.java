package com.luxera.companion.application.builtin.reminder;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.event.LapEventPublisher;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.EventIdMinter;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.contracts.application.ApplicationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 「到点了」—— <b>提醒功能里那个真正的调度器</b>。
 *
 * <p><b>它不是 Subscription, 这两件事必须分清。</b>订阅回答的是"发生了一件事之后, 谁想收到" ——
 * 它是<em>事件的</em>分发。而「15:00 提醒我交房租」里没有任何事件发生, 只有时间到了。
 * 把这两件事混为一谈, 做出来的东西会安静地从不提醒任何人: 订阅建好了, 一切正常, 就是没有人
 * 在 15:00 说话。所以提醒的定时扫描长在<em>拥有提醒数据的那个应用里</em> —— 只有它有不含
 * {@code state_json} 的 {@code due_at} 列, 也只有它能做 {@code WHERE status=? AND due_at<=?}。
 *
 * <p>它做三件事, 缺一不可:
 * <ol>
 *   <li><b>扫</b>到点的 {@code PENDING} 提醒;</li>
 *   <li><b>标</b>{@code DISPATCHED} —— 于是下一次扫描不会重复挑中它, 扫描范围有界;</li>
 *   <li><b>发</b>{@code reminder.due} 事件。事件 id 由 manifest 的 {@code idTemplate}
 *       确定性铸出({@code {uri}#DUE-{reminderId}-{dueKey}}), 所以哪怕这一条因为重启被重发,
 *       数字人侧的去重也会把它短路 —— <b>这正是 {@code triggersAgent} 事件被强制要求
 *       {@code idTemplate} 的原因</b>, 这个应用是第一个真的用上它的。</li>
 * </ol>
 *
 * <p>事件交给 {@link LapEventPublisher}: 类型级闸门、实例级 {@code agentTrigger}、路由
 * (应用说"这条提醒关着谁", 平台查安装表答"其中谁是数字人")三道都在那里, 一条都不在这里重写。
 * 路由答不出收件人时事件不出平台 —— 一个没有数字人的提醒不该凭空产生一次投递。
 */
@Slf4j
@Component
public class ReminderDispatchJob {

    private static final String EVENT_DUE = "reminder.due";

    private final ReminderItemRepository items;
    private final ManifestRegistry manifests;
    private final LapEventPublisher publisher;

    public ReminderDispatchJob(ReminderItemRepository items,
                               ManifestRegistry manifests,
                               LapEventPublisher publisher) {
        this.items = items;
        this.manifests = manifests;
        this.publisher = publisher;
    }

    @Scheduled(cron = "${app.reminder.dispatch-cron:0 */5 * * * *}")
    @Transactional
    public void dispatchDue() {
        try {
            dispatch(LocalDateTime.now());
        } catch (Exception e) {
            // 一次扫描失败不该让调度器停摆: 下一轮还会扫到同一批提醒(它们仍是 PENDING)。
            log.warn("[ReminderDispatch] 到点扫描失败: {}", e.getMessage(), e);
        }
    }

    /** 可被测试直接调用的形式 —— 传一个"现在", 免得用例必须等到真的到点。 */
    int dispatch(LocalDateTime now) {
        ApplicationManifest manifest = manifests.published(ReminderApplication.APP_ID).orElse(null);
        if (manifest == null) {
            log.debug("[ReminderDispatch] 提醒应用未发布, 不扫描");
            return 0;
        }
        List<ReminderItem> due = items.findByStatusAndDueAtLessThanEqualOrderByDueAtAsc(
                ReminderItem.STATUS_PENDING, now);
        if (due.isEmpty()) {
            return 0;
        }

        List<ApplicationEvent> events = new ArrayList<>();
        for (ReminderItem item : due) {
            item.setStatus(ReminderItem.STATUS_DISPATCHED);
            items.save(item);
            events.add(event(manifest, item));
            log.info("[ReminderDispatch] 提醒到点: {} ({})", item.getTitle(), item.getId());
        }
        // 事务提交之后才投递 —— 否则数字人可能读到一条随后回滚的 DISPATCHED。
        publisher.publishAfterCommit(manifest, events);
        return events.size();
    }

    private static ApplicationEvent event(ApplicationManifest manifest, ReminderItem item) {
        String target = ReminderApplication.uriOf(item.getOwnerPrincipalId());
        ObjectNode data = ReminderEvents.data(item, true);
        return new ApplicationEvent(
                EventIdMinter.mint(manifest, EVENT_DUE, target, data),
                EVENT_DUE,
                ReminderApplication.APP_ID,
                target,
                Instant.now(),
                data);
    }
}
