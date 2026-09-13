package com.luxera.companion.application.manifest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 按 manifest 的 {@code events[].idTemplate} 铸造<b>确定性</b>事件 id。
 *
 * <p>为什么这件事要单独抽出来: 应用有<em>两条</em>发事件的路径 —— 动作处理器({@code ctx.emit})
 * 与平台维护任务({@code ReminderDispatchJob} 这类"到点了"的定时扫描)。两条路径必须铸出
 * 同一种 id, 否则同一个原因会生出两个不同的事件 id, 数字人侧的去重立刻失效, 于是"到点提醒"
 * 会被送达两次。
 *
 * <p>把它放在{@code manifest} 包而不是某个应用里, 是因为它不是提醒应用的规则: 任何应用只要
 * 声明了 {@code idTemplate}, 它的定时路径就该走这里, 走的都是同一个实现。
 *
 * <p>模板里可用的占位符: {@code {uri}} 与 {@code {<data 字段名>}}。没声明模板时退回
 * {@code uri#type} —— 仍然确定性, <b>绝不退回随机 UUID</b>: 随机 id 让去重彻底失效, 而那正是
 * {@code ManifestValidator} 对 {@code triggersAgent} 强制要求 {@code idTemplate} 的原因。
 */
public final class EventIdMinter {

    private EventIdMinter() {
    }

    public static String mint(ApplicationManifest manifest, String eventType, String target, JsonNode data) {
        String template = manifest == null ? null : manifest.events().stream()
                .filter(e -> e.type().equals(eventType))
                .map(ApplicationManifest.EventDecl::idTemplate)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse(null);
        if (template == null) {
            return target + "#" + eventType;
        }
        StringBuilder out = new StringBuilder(template);
        replace(out, "{uri}", target);
        if (data != null && data.isObject()) {
            for (var it = data.fields(); it.hasNext(); ) {
                var field = it.next();
                replace(out, "{" + field.getKey() + "}",
                        field.getValue().isValueNode() ? field.getValue().asText("") : "");
            }
        }
        return out.toString();
    }

    /** 占位符在模板里可能出现在任意位置, 全部替换掉 —— 不能只replace 一次。 */
    private static void replace(StringBuilder out, String placeholder, String value) {
        String text = out.toString();
        if (!text.contains(placeholder)) {
            return;
        }
        out.setLength(0);
        out.append(text.replace(placeholder, value == null ? "" : value));
    }
}
