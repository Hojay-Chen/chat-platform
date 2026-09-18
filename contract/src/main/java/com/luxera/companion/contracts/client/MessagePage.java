package com.luxera.companion.contracts.client;

import java.util.List;

/**
 * V2.2 §6.3 第 4/5 项 —— <b>一页消息 + 一个游标</b>, 也就是"微信先给你一屏, 想看更多再拉"这件事。
 *
 * <h2>为什么分页的决定权在平台, 而不是在调用方</h2>
 *
 * <p>调用方说"给我最近的 20 条", 平台说"这是 20 条, 还有更多, 游标是 X"。往上翻的时候
 * 调用方把 X 原样带回来 —— 它<em>不需要</em>知道平台是按时间戳还是按序号分的页, 也
 * <em>不能</em>自己拼一个游标出来。这与真实聊天软件一致: 用户在微信里能做的是"往上滑",
 * 不是"给我第 3 页"。
 *
 * <h2>顺序是倒序的</h2>
 *
 * <p>{@link #messages()} 按时间<b>倒序</b>(最新在前)。这不是随手挑的: 游标是"比这一页更早的
 * 那一批", 而倒序让"下一页"在时间轴上永远朝同一个方向走 —— 正序的话, 第一页要取的是
 * "最后 N 条", 而那句话在 SQL 里是一个和"接下来 N 条"完全不同的查询, 两页之间很容易
 * 出现重复或空洞。
 *
 * @param messages   这一页的消息, 最新在前
 * @param nextCursor 往更早翻的游标; {@code null} 表示没有更多了(与 {@code hasMore=false} 同真同假)
 * @param hasMore    还有没有更早的消息
 */
public record MessagePage(
        List<ClientMessage> messages,
        String nextCursor,
        boolean hasMore
) {
}
