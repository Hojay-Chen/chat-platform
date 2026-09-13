package com.luxera.companion.contracts.chat;

import java.util.List;

/**
 * LAP v2 §64: <b>应用卡片</b> —— 聊天里"一张能点开的应用"。
 *
 * <p>这是 Chat Platform 看见应用时唯一需要的形状。它<b>刻意不是</b>
 * {@code contracts.application.ApplicationView}: 那个类型长在应用平台的发现链上, 带着
 * {@code version} / {@code capabilities} 这些"挑应用"用的字段; 而卡片要回答的是另一个问题 ——
 * <b>现在要不要把它放到这段对话里</b>。两者的字段今天有重叠, 但不是同一个东西: 给
 * {@code ApplicationView} 加一个 {@code iconUrl}, 是一个"应用平台为了聊天改了自己的读模型"的决定,
 * 而那张卡片只是聊天的渲染输入。
 *
 * <h2>可用性用两个布尔, 不用那个五态名</h2>
 * <p>{@code inMarket} 与 {@code allowsNewSession} 直接来自 §4.1 那张表的三列(去掉"已有会话"那一列 ——
 * 卡片只关心"能不能开一个新的")。聊天侧<b>不该</b>去维护一份"哪些状态算在架"的映射: 那份映射
 * 一旦被抄第二遍, 它就有两个真相, 而它们会在某次运营挂起应用之后的五分钟里不一致。
 *
 * @param applicationId 应用 id
 * @param version       这一版的版本号(卡片上显示用)
 * @param name          显示名
 * @param description   一句话说明, 可为 null
 * @param category      分类, 可为 null
 * @param capabilities  它能干什么(能力 id 列表), 供"这段对话里做过什么"这类判断
 * @param inMarket      是否出现在应用市场
 * @param allowsNewSession 是否允许开新会话
 */
public record ApplicationCard(
        String applicationId,
        String version,
        String name,
        String description,
        String category,
        List<String> capabilities,
        boolean inMarket,
        boolean allowsNewSession
) {

    public ApplicationCard {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    /** 能在这段对话里被打开吗 —— 卡片上那个按钮亮不亮。 */
    public boolean openable() {
        return allowsNewSession;
    }
}
