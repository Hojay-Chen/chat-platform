package com.luxera.companion.application.builtin.reminder;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionHandlerKey;
import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.spi.LapApplicationModule;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ResourceView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 提醒/日程 —— <b>第三个参考应用, 也是唯一一个把状态放在自己表里的</b>。
 *
 * <p>它证明的是井字棋与五子棋证明不了的两件事:
 *
 * <ol>
 *   <li><b>{@code backing: APP_OWNED} 真的能用。</b>提醒状态存在 {@code reminder_item} 表里,
 *       读的时候由 {@link ReminderResourceProjector} 投影。平台没有为它做任何特殊处理 ——
 *       {@code ResourceStore} 的读顺序(先表后投影)本来就是这么设计的。</li>
 *   <li><b>跨能力域。</b>前两个应用都在 {@code game.play} 底下, 它属于 {@code reminder.manage}。
 *       发现链的第 1 级这才算真的被走过: 一个只说了"提醒"的意图, 走向的是一个与棋类毫无关系的
 *       应用。</li>
 * </ol>
 *
 * <p><b>它与棋类应用最本质的差别是"归属锚在哪"。</b>棋局锚在会话上 —— 一盘棋随会话开始、
 * 随会话结束, URI 里带 {@code {sessionId}}。提醒锚在一个 <b>principal</b> 上 —— "下周三提醒我
 * 交房租"必须活过任何一个会话。所以它的资源 URI 是 {@code reminder://owner/{ownerId}},
 * 没有会话段, {@code resource.session_id} 为空。这不是绕过平台约定, 而是这个平台本来就允许
 * 两种资源: 会话型与主体型。
 *
 * <p><b>它不注册 {@code PendingActionProvider}。</b>提醒没有"轮到你了"这种局面 —— 到点了该不该
 * 说话、说什么, 由数字人自己决定(见 {@code ApplicationNotificationBridge}), 而不是由一个应用
 * 替它宣布"你现在必须做点什么"。默认的空实现正好表达这件事。
 */
@Component
public class ReminderApplication implements LapApplicationModule {

    static final String APP_ID = "com.luxera.reminder";
    static final String VERSION = "1.0.0";
    static final String RESOURCE_TYPE = "reminder.inbox";

    private static final String ACTION_CREATE = "reminder.create";
    private static final String ACTION_UPDATE = "reminder.update";
    private static final String ACTION_COMPLETE = "reminder.complete";
    private static final String ACTION_CANCEL = "reminder.cancel";
    private static final String ACTION_LIST = "reminder.list";

    private static final String EVENT_CREATED = "reminder.created";
    private static final String EVENT_DUE = "reminder.due";
    private static final String EVENT_COMPLETED = "reminder.completed";
    private static final String EVENT_CANCELLED = "reminder.cancelled";

    private final ReminderItemRepository items;

    public ReminderApplication(ReminderItemRepository items) {
        this.items = items;
    }

    @Override
    public String manifestLocation() {
        return "applications/reminder/1.0.0/application-manifest.json";
    }

    @Override
    public void registerHandlers(ActionHandlerRegistry registry) {
        registry.register(key(ACTION_CREATE), this::create);
        registry.register(key(ACTION_UPDATE), this::update);
        registry.register(key(ACTION_COMPLETE), this::complete);
        registry.register(key(ACTION_CANCEL), this::cancel);
        registry.register(key(ACTION_LIST), this::list);
    }

    private static ActionHandlerKey key(String actionId) {
        return ActionHandlerKey.of(APP_ID, VERSION, actionId);
    }

    /** 某个主体的收件箱 URI —— 应用自己也需要它(事件里要带上"这属于谁")。 */
    static String uriOf(String ownerId) {
        return "reminder://owner/" + ownerId;
    }

    // ─────────────────────────── 动作 ───────────────────────────

    private ActionOutcome create(ActionHandlerContext ctx) {
        String ownerId = ownerIdOf(ctx);
        if (ownerId == null) {
            return notOwner(ctx);
        }

        String title = trimmed(ctx.inputText("title"));
        if (title == null) {
            return ActionOutcome.of(ActionStatus.INVALID_ARGUMENT,
                    "TITLE_REQUIRED", "提醒必须有一个标题");
        }
        LocalDateTime dueAt = parseTime(ctx.inputText("dueAt"));
        if (dueAt == null) {
            return ActionOutcome.of(ActionStatus.INVALID_ARGUMENT,
                    "DUE_AT_REQUIRED", "dueAt 必填, 形如 2026-09-12T15:00");
        }

        String type = trimmed(ctx.inputText("type"));
        if (type == null) type = ReminderItem.TYPE_USER_SET;
        String companionId = trimmed(ctx.inputText("companionId"));

        // 生日提醒的"每年最多一条"由应用判定, 不由调用方判定 —— 调用方(数字人的生日服务)
        // 每天被 cron 叫醒一次, 它没有义务自己记住"今年已经建过了"。规则属于数据, 数据在这里。
        if (ReminderItem.TYPE_BIRTHDAY.equals(type) && companionId != null) {
            Optional<ReminderItem> existing = items
                    .findByOwnerPrincipalIdAndCompanionIdAndTypeAndStatus(
                            ownerId, companionId, type, ReminderItem.STATUS_PENDING)
                    .stream().findFirst();
            if (existing.isPresent()) {
                return ActionOutcome.success(detail(existing.get()));
            }
        }

        ReminderItem item = new ReminderItem();
        item.setOwnerPrincipalType(ctx.principal().principalType().name());
        item.setOwnerPrincipalId(ownerId);
        item.setCompanionId(companionId);
        item.setUserId(ctx.userId());
        item.setType(type);
        item.setTitle(title);
        item.setNote(trimmed(ctx.inputText("note")));
        item.setDueAt(dueAt);
        item.setStatus(ReminderItem.STATUS_PENDING);
        item.setRepeatRule(trimmed(ctx.inputText("repeatRule")));
        ReminderItem saved = items.save(item);

        ctx.emit(EVENT_CREATED, eventData(saved, false));
        return ActionOutcome.success(detail(saved));
    }

    private ActionOutcome update(ActionHandlerContext ctx) {
        String ownerId = ownerIdOf(ctx);
        if (ownerId == null) {
            return notOwner(ctx);
        }
        ReminderItem item = find(ctx, ownerId);
        if (item == null) {
            return notFound(ctx);
        }
        // 判据是 closed() 而不是 pending(): 已经派发过一次的提醒照样可以改期 —— 用户说
        // "下周三那件事改到周五"时, 那条提醒在库里往往已经是 DISPATCHED。用 pending() 会把它
        // 判成"改不了", 而这恰恰是最常见的一次改期。
        if (item.closed()) {
            return ActionOutcome.fail("REMINDER_CLOSED", "这条提醒已经结束(" + item.getStatus() + "), 改不了了");
        }
        String title = trimmed(ctx.inputText("title"));
        if (title != null) item.setTitle(title);
        String note = ctx.inputText("note");
        if (note != null) item.setNote(trimmed(note));
        String due = ctx.inputText("dueAt");
        if (due != null) {
            LocalDateTime dueAt = parseTime(due);
            if (dueAt == null) {
                return ActionOutcome.of(ActionStatus.INVALID_ARGUMENT,
                        "INVALID_DUE_AT", "dueAt 形如 2026-09-12T15:00");
            }
            item.setDueAt(dueAt);
            // 改期 = 重新计时: 已经派发过的提醒改到未来, 必须回到 PENDING 才会再响一次。
            item.setStatus(ReminderItem.STATUS_PENDING);
        }
        return ActionOutcome.success(detail(items.save(item)));
    }

    private ActionOutcome complete(ActionHandlerContext ctx) {
        return close(ctx, ReminderItem.STATUS_DONE, EVENT_COMPLETED);
    }

    private ActionOutcome cancel(ActionHandlerContext ctx) {
        return close(ctx, ReminderItem.STATUS_CANCELLED, EVENT_CANCELLED);
    }

    private ActionOutcome close(ActionHandlerContext ctx, String status, String eventType) {
        String ownerId = ownerIdOf(ctx);
        if (ownerId == null) {
            return notOwner(ctx);
        }
        ReminderItem item = find(ctx, ownerId);
        if (item == null) {
            return notFound(ctx);
        }
        if (status.equals(item.getStatus())) {
            return ActionOutcome.success(detail(item));   // 幂等: 已经是这个状态了
        }
        item.setStatus(status);
        ReminderItem saved = items.save(item);
        ctx.emit(eventType, eventData(saved, false));
        return ActionOutcome.success(detail(saved));
    }

    /**
     * 读自己的收件箱。{@code ownerIdOf} 在这里<em>不是可选的</em> —— 少了它, 这一条就是本应用里
     * 最直接的越权读: 任何装了提醒应用的人只要把 URI 里的 id 换成别人的, 就能拿到别人全部的提醒,
     * 而权限判定全程不会拦(它只看"你能不能调 {@code reminder.list}")。
     *
     * <p>"读"看起来比"写"无害, 所以最容易在这里省掉归属检查 —— 而提醒恰恰是那种"读到了就等于
     * 知道了"的数据。
     */
    private ActionOutcome list(ActionHandlerContext ctx) {
        String ownerId = ownerIdOf(ctx);
        if (ownerId == null) {
            return notOwner(ctx);
        }
        ResourceView current = ctx.currentResource();
        if (current != null && current.state() != null) {
            return ActionOutcome.success(current.state());
        }
        ObjectNode empty = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        empty.put("ownerId", ownerId);
        empty.put("total", 0);
        empty.put("pending", 0);
        empty.putNull("nextDueAt");
        empty.putArray("items");
        return ActionOutcome.success(empty);
    }

    // ─────────────────────────── 归属 ───────────────────────────

    /**
     * 谁是这个资源的主人 —— <b>并且确认调用方就是他</b>。不是他则返回 {@code null}。
     *
     * <p>URI 里写着 ownerId, 而 URI 是调用方给的。所以"读自己的收件箱"这件事必须在这里被检查:
     * 权限判定管的是"你能不能调这个动作", 它不看 URI 里的那一段。少了这一步, 任何一个装了提醒
     * 应用的人都能读别人的收件箱 —— 一个纯粹的 IDOR。
     */
    private static String ownerIdOf(ActionHandlerContext ctx) {
        return ReminderResourceProjector.ownerIdIn(ctx.target())
                .filter(ownerId -> ownerId.equals(ctx.principalId()))
                .orElse(null);
    }

    private static ActionOutcome notOwner(ActionHandlerContext ctx) {
        return ActionOutcome.of(ActionStatus.DENIED, "NOT_RESOURCE_OWNER",
                "提醒属于 " + ctx.target() + " 的主人, 不是你");
    }

    private ActionOutcome notFound(ActionHandlerContext ctx) {
        return ActionOutcome.of(ActionStatus.NOT_FOUND, "REMINDER_NOT_FOUND",
                "没有这条提醒: " + ctx.inputText("reminderId"));
    }

    /** 按 id 取, 并确认它属于这个 owner —— 又一次 IDOR 检查, 因为 id 也是调用方给的。 */
    private ReminderItem find(ActionHandlerContext ctx, String ownerId) {
        String reminderId = trimmed(ctx.inputText("reminderId"));
        if (reminderId == null) {
            return null;
        }
        return items.findById(reminderId)
                .filter(item -> ownerId.equals(item.getOwnerPrincipalId()))
                .orElse(null);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static ObjectNode detail(ReminderItem item) {
        ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("id", item.getId());
        node.put("type", item.getType());
        node.put("title", item.getTitle());
        node.put("dueAt", ReminderItem.iso(item.getDueAt()));
        node.put("status", item.getStatus());
        return node;
    }

    /** 见 {@link ReminderEvents}: 定时派发路径与这里必须发同形的事件。 */
    private static ObjectNode eventData(ReminderItem item, boolean dispatch) {
        return ReminderEvents.data(item, dispatch);
    }

    private static String trimmed(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 接受 {@code 2026-09-12T15:00}、{@code 2026-09-12 15:00} 与 {@code 2026-09-12T15:00:30}。 */
    static LocalDateTime parseTime(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.trim().replace(' ', 'T');
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (Exception ignored) {
                // 换下一个格式
            }
        }
        return null;
    }
}
