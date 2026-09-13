package com.luxera.companion.application.builtin.reminder;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;

/**
 * 提醒事件的载荷 —— <b>两条发事件的路径共用同一份</b>。
 *
 * <p>提醒有两条路径会发事件: 动作处理器(用户/数字人主动创建、完成)与平台维护任务的定时扫描
 * ({@link ReminderDispatchJob} 发现"到点了")。两条路径发的是<em>同名同形</em>的事件, 载荷字段
 * 必须逐字一致 —— 否则 {@code {dueKey}} 这种占位符在一处填得上、在另一处填不上, 铸出来的
 * 事件 id 就不同, 去重失效, 同一件事被送达两次。
 *
 * <p>放一个类而不是各写一遍, 是因为"两处模板必须一致"这种约束靠注释是守不住的。
 */
final class ReminderEvents {

    /** 到点事件 id 模板里的 {@code {dueKey}}。用紧凑格式是因为它会进事件 id, 不该带冒号。 */
    private static final DateTimeFormatter DUE_KEY = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm");

    private ReminderEvents() {
    }

    /**
     * @param dispatch 这一次是不是"该叫醒数字人"的派发。只有派发才带
     *                 {@code notifyPrincipalIds} 与 {@code agentTrigger} ——
     *                 创建/完成事件是给审计与订阅看的, 不是叫醒任何人的理由。
     */
    static ObjectNode data(ReminderItem item, boolean dispatch) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("reminderId", item.getId());
        data.put("status", item.getStatus());
        data.put("dueAt", ReminderItem.iso(item.getDueAt()));
        if (dispatch) {
            data.put("dueKey", dueKey(item));
            // 两级闸门的第二级: manifest 说"这类事件可以唤起数字人", 这里说"这一次要"。
            data.put("agentTrigger", true);
            // 应用只说"这条提醒关着谁", 谁是数字人由平台查安装表回答(AgentRouteResolver)。
            ArrayNode notify = data.putArray("notifyPrincipalIds");
            if (StringUtils.hasText(item.getCompanionId())) {
                notify.add(item.getCompanionId());
                // 「到点了该以什么形式出现在用户面前」—— 应用只说这件事值得被看见, 以及它长什么样。
                // 谁来把它变成一条通知、存在哪张表里, 是数字人那边的事(见 ApplicationNotificationBridge)。
                // 这一块是给数字人看的, 不是给平台看的: 平台不认识 notify, 照原样透传。
                ObjectNode block = data.putObject("notify");
                block.put("userId", item.getUserId());
                block.put("type", item.getType());
                block.put("title", item.getTitle());
                block.put("content", item.getNote());
            }
        }
        return data;
    }

    static String dueKey(ReminderItem item) {
        return item.getDueAt() == null ? item.getId() : DUE_KEY.format(item.getDueAt());
    }
}
