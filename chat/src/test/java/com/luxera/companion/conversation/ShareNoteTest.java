package com.luxera.companion.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附言的**形状** —— §10 那个「来玩吗?」输入框里写下来的东西, 最终会变成一条**别人会看到**
 * 的消息。
 *
 * <p>这是这条分享链上唯一由用户随手输入决定的字段, 所以它也是唯一一处"判宽了会出丑、判窄了
 * 会把用户写的话悄悄吃掉"的地方。两种都不可接受, 所以每一档都有一条用例。
 *
 * <p>它<b>不是</b>防注入: 那段文本在客户端永远以文本节点渲染, 不会被当成 HTML。这里做的
 * 是版面与可诊断性 —— 见 {@code ConversationApplicationService.sanitizeNote} 那五步。
 */
class ShareNoteTest {

    @Test
    void 没有附言就是空串而不是null() {
        // null 会让调用方在拼 content 时被迫判一次空; 空串让"没写附言"与"写了空白"归一。
        assertEquals("", ConversationApplicationService.sanitizeNote(null));
        assertEquals("", ConversationApplicationService.sanitizeNote(""));
        assertEquals("", ConversationApplicationService.sanitizeNote("   "));
        assertEquals("", ConversationApplicationService.sanitizeNote("\n\n\t "));
    }

    @Test
    void 首尾空白与多余空格被收掉() {
        assertEquals("来玩吗?", ConversationApplicationService.sanitizeNote("  来玩吗?  "));
        assertEquals("来 玩 吗", ConversationApplicationService.sanitizeNote("来\t\t玩  吗"));
    }

    /**
     * 横向空白收的是<b>一整类</b>, 不是只有 ASCII 空格。
     *
     * <p>三个兄弟在气泡里长得与普通空格一模一样, 所以只收普通空格等于没做这件事:
     * 用户从别处粘一段话进来, 中间照样能出现一截撑开版面的空档。
     *
     * <ul>
     *   <li>U+00A0 不换行空格 —— 手机键盘的智能标点、网页复制粘贴里极常见;</li>
     *   <li>U+3000 全角空格 —— 中文输入法直接打得出来;</li>
     *   <li>以及制表符 \t —— 它在 C0 里, 不属于 Zs, 所以要单独列出来。</li>
     * </ul>
     *
     * <p>反斜杠转义而不是字面量: 这几个字符在源码里是看不见的, 而"看不见"
     * 正是它们能悄悄撑开一条消息的原因。
     */
    @Test
    void 不换行空格与全角空格一并收掉() {
        assertEquals("来 玩 吗", ConversationApplicationService.sanitizeNote("来\u00a0\u00a0玩 吗"));
        assertEquals("来 玩 吗", ConversationApplicationService.sanitizeNote("来\u3000玩 吗"));
        assertEquals("来 玩 吗", ConversationApplicationService.sanitizeNote("来\t\u00a0玩 吗"));
        // 收完是**普通空格**, 不是删掉 —— 删掉会把两个词粘成一个。
        assertEquals("ab", ConversationApplicationService.sanitizeNote("a\u3000b").replace(" ", ""));
    }

    /**
     * 换行是<b>正常输入</b> —— 用户会在附言里分行。压掉它等于替用户改写他说的话。
     */
    @Test
    void 单个换行保留_连续空行压成一个() {
        assertEquals("第一行\n第二行",
                ConversationApplicationService.sanitizeNote("第一行\n第二行"));
        assertEquals("第一行\n\n第二行",
                ConversationApplicationService.sanitizeNote("第一行\n\n\n\n\n第二行"));
    }

    /**
     * 控制字符被删掉, 而不是替换成一个可见的占位符。
     *
     * <p>零宽字符在气泡里什么都看不见, 却会让这段文本在日志、终端、告警里表现异常 ——
     * 一个只用来搞坏别人排查过程的字符没有保留的理由。
     */
    @Test
    void 控制字符被删掉() {
        // 写成八进制转义而不是字面量: 字面量在编辑器里是看不见的, 而"看不见的字符"
        // 正是这个方法在删的东西 —— 一条看不见的断言失败没法排查。
        assertEquals("abc", ConversationApplicationService.sanitizeNote("a\0b\7c"));
        assertEquals("ab", ConversationApplicationService.sanitizeNote("a\37\177b"));
        // \t 与 \n 是例外 —— 它们是正常输入。\t 会被压成普通空格, \n 原样保留。
        assertEquals("a b", ConversationApplicationService.sanitizeNote("a\tb"));
        assertEquals("a\nb", ConversationApplicationService.sanitizeNote("a\nb"));
    }

    @Test
    void 超长被截断到上限() {
        String long_ = "字".repeat(ConversationApplicationService.MAX_NOTE + 50);

        String out = ConversationApplicationService.sanitizeNote(long_);

        assertEquals(ConversationApplicationService.MAX_NOTE, out.length());
    }

    /**
     * 截断发生在**裁剪之后** —— 否则 200 个空格会把真正的内容挤出去。
     *
     * <p>顺序反了的症状很隐蔽: 一个用户在结尾打了几个空格, 他的附言就少了几个字, 而这条
     * 路径只有在他恰好写满上限时才会暴露。
     */
    @Test
    void 先裁剪再截断() {
        String padded = "  " + "字".repeat(ConversationApplicationService.MAX_NOTE) + "  ";

        String out = ConversationApplicationService.sanitizeNote(padded);

        assertEquals(ConversationApplicationService.MAX_NOTE, out.length());
        assertTrue(out.startsWith("字"), "开头的空白不该占掉一个字的额度");
    }

    /** 恰好等于上限时不截断 —— 边界另一侧。 */
    @Test
    void 恰好等于上限时原样保留() {
        String exact = "字".repeat(ConversationApplicationService.MAX_NOTE);

        assertEquals(exact, ConversationApplicationService.sanitizeNote(exact));
    }
}
