package com.luxera.companion.application.builtin.reminder;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 一条提醒 —— <b>提醒的唯一数据源</b>。
 *
 * <p>它是 LAP v1 里第一个 {@code backing: APP_OWNED} 的资源: 状态不长在 {@code resource} 表里,
 * 而是长在自己这张真表上, 读的时候由 {@link ReminderResourceProjector} 投影成
 * {@code ResourceView}。<b>没有双写</b> —— {@code resource} 表里根本没有提醒的行,
 * 也就不存在"两张表谁是真理"这种必然漂移的问题。
 *
 * <p><b>为什么提醒非得有真表。</b>"找出所有 {@code PENDING} 且已到点的提醒"是提醒功能的
 * 全部意义所在, 而这句话是 {@code WHERE status = ? AND due_at <= ?}。把它塞进
 * {@code resource.state_json} 里, 这个查询就只能全表扫描 + 在 JVM 里过滤 —— 棋盘的
 * {@code RESOURCE_STORE} 形态适合棋盘(一次只读一行), 但撑不起提醒。
 *
 * <p>{@code owner_principal_type} 与 {@code owner_principal_id} 一起构成归属: 提醒属于一个
 * <em>principal</em>(真人是 userId, 数字人是 companionId), 不属于某个会话。这是它与棋局
 * 最本质的差别 —— 一盘棋随会话开始和结束, 一条"下周三提醒我交房租"要活过会话。
 */
@Entity
@Table(name = "reminder_item", indexes = {
        @Index(name = "idx_reminder_owner", columnList = "owner_principal_id"),
        @Index(name = "idx_reminder_due", columnList = "status,due_at")
})
@Getter
@Setter
public class ReminderItem {

    /** 还没到点, 或者到点了但还没被派发。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已派发过一次。派发是幂等的: 事件 id 由 manifest 的 idTemplate 决定, 重复派发会被去重。 */
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    /** 用户/数字人确认处理完了。 */
    public static final String STATUS_DONE = "DONE";
    /** 用户取消了。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String TYPE_USER_SET = "user_set";
    public static final String TYPE_BIRTHDAY = "birthday";
    public static final String TYPE_CHECK_IN = "check_in";

    @Id
    @Column(length = 36)
    private String id;

    /** HUMAN / AGENT / SYSTEM —— 与平台的角色词汇一致, 不另造一套。 */
    @Column(name = "owner_principal_type", nullable = false, length = 16)
    private String ownerPrincipalType;

    @Column(name = "owner_principal_id", nullable = false, length = 64)
    private String ownerPrincipalId;

    /** 为谁设的。真人的提醒通常带着一位数字人, 但它是<em>属性</em>不是归属 —— 归属是 owner。 */
    @Column(name = "companion_id", length = 64)
    private String companionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(nullable = false, length = 32)
    private String type = TYPE_USER_SET;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 500)
    private String note;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    /** PENDING | DISPATCHED | DONE | CANCELLED */
    @Column(nullable = false, length = 32)
    private String status = STATUS_PENDING;

    /** 重复规则(RRULE 风格)。当前只存不用 —— 出现"每周三"这种需求时它是唯一不需要改表的地方。 */
    @Column(name = "repeat_rule", length = 64)
    private String repeatRule;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 还没派发 —— 定时扫描只挑这一种。
     *
     * <p>注意它<em>不是</em>"还能不能改"的判据: 已经派发过的提醒照样可以改期, 而且改期必须
     * 把它送回 {@code PENDING}(见 {@link #closed()})。
     */
    public boolean pending() {
        return STATUS_PENDING.equals(status);
    }

    /** 已经了结 —— 办完了或取消了。只有这两种状态改不动。 */
    public boolean closed() {
        return STATUS_DONE.equals(status) || STATUS_CANCELLED.equals(status);
    }

    /**
     * 对外的本地时间写法 —— <b>秒为 0 时省略秒</b>。
     *
     * <p>与 {@code LocalDateTime.toString()} 同一规则不是巧合: manifest 与 {@code agentHint}
     * 对调用方承诺的形状是 {@code 2026-09-12T15:00}, 而 {@code DateTimeFormatter.ISO_LOCAL_DATE_TIME}
     * 输出的是 {@code 2026-09-12T15:00:00}。只差两个字符, 但它出现在每一个 {@code dueAt} 上,
     * 而两种写法在"解析后相等、字符串不相等"的意义上都对 —— 于是这种不一致既不会报错,
     * 也不会被测出来, 只会一直在那里。
     */
    public static String iso(LocalDateTime time) {
        return time == null ? null : time.toString();
    }
}
