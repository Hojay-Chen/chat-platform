package com.luxera.companion.application.builtin.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.resource.ResourceProjector;
import com.luxera.companion.contracts.application.ResourceView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 把 {@code reminder_item} 表投影成一个 {@code ResourceView} —— {@code APP_OWNED} 的读出口。
 *
 * <p>它只做一件事: <b>表 → 视图, 单向。</b> 写路径根本不经过这里(应用自己的 handler 写自己的
 * 表), 所以投影永远不会与被投影的东西漂移 —— 没有第二份状态可以漂。
 *
 * <p>资源身份是 {@code reminder://owner/{ownerId}} —— 一个人的<em>收件箱</em>, 而不是"某一条提醒"。
 * 这个形状是刻意的: 提醒天然是集合(读的时候几乎总是"我有哪些要办的事", 而不是"第 47 号提醒是谁"),
 * 而集合型资源在平台上完全合法 —— {@code resource.session_id} 本来就是可空的, 归属锚在
 * {@code ownerId} 这一段上。棋局必须是单数(一盘棋一条 URI, 因为它是 CAS 的对象), 提醒不必。
 */
@Component
public class ReminderResourceProjector implements ResourceProjector {

    /** 与 manifest 的 {@code resources[].uriTemplate} 必须一致 —— 见 {@link #supports}。 */
    public static final String URI_TEMPLATE = "reminder://owner/{ownerId}";
    private static final String VARIABLE = "ownerId";

    private final ReminderItemRepository items;
    private final ObjectMapper objectMapper;

    public ReminderResourceProjector(ReminderItemRepository items, ObjectMapper objectMapper) {
        this.items = items;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String uri) {
        return ownerIdIn(uri).isPresent();
    }

    @Override
    public Optional<ResourceView> project(String uri) {
        return ownerIdIn(uri).map(ownerId -> {
            List<ReminderItem> all = items.findByOwnerPrincipalIdOrderByDueAtAsc(ownerId);
            return new ResourceView(
                    uri,
                    ReminderApplication.RESOURCE_TYPE,
                    ReminderApplication.APP_ID,
                    null,                       // 提醒不属于某个会话 —— 见类注释
                    state(ownerId, all),
                    version(all),
                    updatedAt(all),
                    null);                      // agentHint 由网关按 manifest 回填
        });
    }

    /** 从 URI 里取 {@code {ownerId}}; 不是这个形状就返回空(交给别的投影器)。 */
    static Optional<String> ownerIdIn(String uri) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        return com.luxera.companion.application.manifest.UriTemplate
                .variable(URI_TEMPLATE, uri, VARIABLE);
    }

    private ObjectNode state(String ownerId, List<ReminderItem> all) {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("ownerId", ownerId);
        state.put("total", all.size());

        long pending = all.stream().filter(ReminderItem::pending).count();
        state.put("pending", pending);

        ArrayNode array = state.putArray("items");
        for (ReminderItem item : all) {
            array.add(item(item));
        }

        all.stream()
                .filter(ReminderItem::pending)
                .map(ReminderItem::getDueAt)
                .min(Comparator.naturalOrder())
                .ifPresentOrElse(next -> state.put("nextDueAt", ReminderItem.iso(next)),
                        () -> state.putNull("nextDueAt"));
        return state;
    }

    private ObjectNode item(ReminderItem item) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", item.getId());
        node.put("type", item.getType());
        node.put("title", item.getTitle());
        node.put("note", item.getNote());
        node.put("dueAt", ReminderItem.iso(item.getDueAt()));
        node.put("status", item.getStatus());
        node.put("companionId", item.getCompanionId());
        node.put("createdAt", ReminderItem.iso(item.getCreatedAt()));
        return node;
    }

    /**
     * 资源版本。这不是 CAS 令牌(投影资源没有 CAS 写路径), 只是一个"内容变了没有"的单调量:
     * 取最新一条的 {@code updated_at} 毫秒。空收件箱是 0。
     */
    private static long version(List<ReminderItem> all) {
        return all.stream()
                .map(ReminderItem::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .mapToLong(ReminderResourceProjector::millis)
                .max()
                .orElse(0L);
    }

    private static Instant updatedAt(List<ReminderItem> all) {
        return all.stream()
                .map(ReminderItem::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(ReminderResourceProjector::instant)
                .orElse(null);
    }

    private static long millis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static Instant instant(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
