package com.luxera.companion.conversation;

import com.luxera.chatframework.ChatPlatformTestApplication;
import com.luxera.chatframework.ChatTestApplicationCatalog;
import com.luxera.companion.contracts.chat.ApplicationCard;
import com.luxera.companion.contracts.chat.ApplicationInvitation;
import com.luxera.companion.contracts.chat.ApplicationSessionView;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAP v2 §63/§64 验收: <b>聊天平台不知道任何具体应用</b>。
 *
 * <p>这一条有两种证法, 而它们证明的不是同一件事:
 *
 * <ol>
 *   <li><b>行为证明</b> —— 让整条链路跑在一个本仓库从未见过的应用
 *       ({@link ChatTestApplicationCatalog#APPLICATION_ID}) 上。它证明了"聊天侧不需要认识
 *       任何应用", 但证明不了"它没有偷偷认识一个"。</li>
 *   <li><b>源码证明</b> —— 扫描聊天侧的集成代码, 断言里面不出现任何一个真实应用的 id 或
 *       动作名。它证明了"没有偷偷认识", 但证明不了"不认识也能跑"。</li>
 * </ol>
 *
 * <p>两条都要, 因为一条的漏洞正好是另一条的强项。这正是 §63 那条要求被认真对待的样子 ——
 * 只写注释说"我们不认识具体应用", 是没有任何东西在守的。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class ChatApplicationPortTest {

    @Autowired
    ConversationApplicationService applications;
    @Autowired
    ConversationRepository conversations;
    @Autowired
    com.luxera.chatframework.ChatTestApplicationCatalog catalogue;

    private String companionId;
    private String conversationId;

    @BeforeEach
    void setUp() {
        catalogue.reset();
        companionId = UUID.randomUUID().toString();
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId("r12-user");
        conv.setCompanionId(companionId);
        conv.setTitle("R12");
        conversations.save(conv);
        conversationId = conv.getId();
    }

    // ─────────────────────────── 行为证明 ───────────────────────────

    @Test
    void 一个本仓库从未见过的应用可以走完整条链路() {
        ConversationApplicationService.ContextView before =
                applications.context("r12-user", companionId, conversationId);
        assertEquals(List.of(ChatTestApplicationCatalog.APPLICATION_ID),
                before.openable().stream().map(ApplicationCard::applicationId).toList());
        assertTrue(before.open().isEmpty());

        ConversationApplicationService.OpenResult opened = applications.open("r12-user", companionId,
                conversationId, new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null));
        assertEquals(ChatTestApplicationCatalog.APPLICATION_ID, opened.launch().session().applicationId());
        assertEquals("OWNER", opened.launch().participant().role());

        ConversationApplicationService.ContextView after =
                applications.context("r12-user", companionId, conversationId);
        assertEquals(List.of(opened.launch().session().sessionId()),
                after.open().stream().map(ApplicationSessionView::sessionId).toList());

        ApplicationInvitation invitation = applications.share("r12-user", companionId, conversationId,
                opened.launch().session().sessionId(), "MEMBER", 3).invitation();
        assertNotNull(invitation.token());
        assertTrue(invitation.joinUrl().endsWith(invitation.token()));
    }

    @Test
    void 开应用时把对话id原样传给了应用平台() {
        applications.open("r12-user", companionId, conversationId,
                new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null));
        assertEquals(1, catalogue.launchRequests.size());
        // §85: 会话行上要记住它属于哪段对话 —— 而这件事只有一个地方能做: 传下去。
        assertEquals(conversationId, catalogue.launchRequests.get(0).conversationId());
    }

    /**
     * 端口上自报的身份是<em>写下来的</em>真人, 不是"没写所以默认"。
     *
     * <p>假实现({@link ChatTestApplicationCatalog#requireIdentity})会把没有
     * {@code principalType} 的上下文直接拒掉, 与真的应用平台
     * ({@code InternalPrincipalResolver})行为一致 —— 所以这条断言在假世界里也是真的:
     * 只要聊天侧哪天改成省略类型, 上面那几个测试会一起红。
     */
    @Test
    void 端口上自报的身份是写下来的真人_不是默认值() {
        applications.open("r12-user", companionId, conversationId,
                new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null));

        assertEquals(1, catalogue.launchContexts.size(), "open 应当把身份递给应用平台");
        var identity = catalogue.launchContexts.get(0);
        assertEquals(PrincipalType.HUMAN, identity.principalType());
        assertEquals("r12-user", identity.principalId());
        assertNotNull(identity.correlationId(), "每一次调用都要带一个 correlationId");
    }

    @Test
    void 应用被下架之后卡片从可开列表里消失_但对话里已开的会话还在() {
        ConversationApplicationService.OpenResult opened = applications.open("r12-user", companionId,
                conversationId, new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null));

        catalogue.allowsNewSession = false;

        ConversationApplicationService.ContextView view =
                applications.context("r12-user", companionId, conversationId);
        assertTrue(view.openable().isEmpty(), "下架的应用不该再给出一张能按的卡片");
        assertEquals(1, view.open().size(), "但场上那一局还在 —— 平台不替运营惩罚用户");
        assertEquals(opened.launch().session().sessionId(), view.open().get(0).sessionId());
    }

    // ─────────────────────────── 源码证明 ───────────────────────────

    /**
     * 聊天侧的集成代码里不出现任何一个真实应用的 id / 能力 / 动作名。
     *
     * <p>扫的是 <b>源码文本</b>而不是字节码: 这一条要防的不是一个 import, 而是一个<em>字符串</em>
     * —— {@code if ("com.luxera.tictactoe".equals(appId))} 在编译后的字节码里和别的东西长得
     * 一模一样, 而它在源码里一眼就能看见。
     *
     * <p>被扫的文件是<em>白名单列出</em>的, 不是"整个模块" —— 模块里还有 {@code simulator}、
     * {@code event} 这些与应用无关的包, 而一条会误报的规则最后一定会被删掉。
     *
     * <p><b>注释不算。</b> 一段说"这个类不认识井字棋"的注释是一句<em>关于</em>应用的陈述,
     * 而这条规则管的是代码<em>认识</em>什么。把注释也算进去, 规则会逼着人把话说含糊 ——
     * 而它要防的东西(一个写死的 {@code applicationId})一个都不会因此消失。
     */
    @Test
    void 聊天侧的集成代码里没有任何一个具体应用的名字() throws IOException {
        List<Path> integration = List.of(
                Path.of("src/main/java/com/luxera/companion/conversation/ConversationApplicationService.java"),
                Path.of("src/main/java/com/luxera/companion/conversation/ConversationApplicationController.java"));

        // 本仓库真实存在的应用与它们的能力/动作 —— 聊天侧<em>代码里</em>提起任何一个都算越界。
        List<String> forbidden = List.of(
                "com.luxera.tictactoe", "com.luxera.gomoku", "com.luxera.reminder",
                "game.make_move", "game.create", "game.state", "game.surrender",
                "gomoku.", "reminder.", "game.play", "reminder.manage", "tictactoe");

        for (Path file : integration) {
            assertTrue(Files.exists(file), "聊天侧的集成文件不见了: " + file.toAbsolutePath());
            String code = withoutComments(Files.readString(file));
            for (String word : forbidden) {
                assertFalse(code.contains(word),
                        file.getFileName() + " 的代码里出现了具体应用的词汇: " + word
                                + " —— §63: 聊天平台不知道任何具体应用");
            }
        }
    }

    /** 去掉 {@code /* … *}{@code /} 与 {@code // …} —— 只留代码。 */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** 顺带把"集成面就是这两个文件"本身钉住 —— 新开一个文件时这条会提醒去更新上一条。 */
    @Test
    void 集成面的文件清单没有悄悄变多() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/java/com/luxera/companion/conversation"))) {
            List<String> named = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.contains("Application"))
                    .sorted()
                    .toList();
            assertEquals(List.of(
                            "ConversationApplicationController.java",
                            "ConversationApplicationExceptionHandler.java",
                            "ConversationApplicationService.java"),
                    named,
                    "聊天侧的应用集成面变了 —— 上面的白名单要一起改, 否则它会悄悄地守不住新文件");
        }
    }
}
