package com.luxera.companion.application.builtin.reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code reminder_item} 的仓库 —— 应用自己的表, 应用自己读写。
 *
 * <p>平台不投影写、不代劳写: {@code APP_OWNED} 的语义就是"状态在你自己那儿, 你负责它的
 * 一致性"。投影只读不写, 所以不存在"平台写一遍、应用再写一遍"的双写。
 *
 * <p>三个查询对应三件真实的事:
 * <ul>
 *   <li>{@link #findByOwnerPrincipalIdOrderByDueAtAsc} —— 某人的收件箱(读资源时用);</li>
 *   <li>{@link #findByStatusAndDueAtLessThanEqualOrderByDueAtAsc} —— <b>到点了</b>。
 *       这一条就是"提醒必须有真表"的全部理由: JSON 列索引不了它。</li>
 *   <li>{@link #findByOwnerPrincipalIdAndCompanionIdAndTypeAndStatus} —— 生日提醒去重
 *       (同一个伴侣在同一个用户下, 不该有第二条待触发的生日提醒)。</li>
 * </ul>
 */
public interface ReminderItemRepository extends JpaRepository<ReminderItem, String> {

    List<ReminderItem> findByOwnerPrincipalIdOrderByDueAtAsc(String ownerPrincipalId);

    List<ReminderItem> findByStatusAndDueAtLessThanEqualOrderByDueAtAsc(String status, LocalDateTime now);

    List<ReminderItem> findByOwnerPrincipalIdAndCompanionIdAndTypeAndStatus(
            String ownerPrincipalId, String companionId, String type, String status);

    List<ReminderItem> findByCompanionIdAndStatus(String companionId, String status);
}
