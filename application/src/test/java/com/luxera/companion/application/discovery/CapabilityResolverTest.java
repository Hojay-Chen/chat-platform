package com.luxera.companion.application.discovery;

import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发现链的第一级: <b>意图 → 能力 → 候选应用</b>。
 *
 * <p>类名沿用了设计文档里的 {@code CapabilityResolver}, 但实现它的类是
 * {@link ActionGateway} —— 发现不是一条独立的链路, 它就是网关的一部分。方案反复强调"不存在
 * 独立的 Agent API", 同一个道理: 也不存在"Agent 专用的发现服务"。
 *
 * <p><b>这个测试要钉住的是一句具体的话: "你绝对不能把 50000 个 action 全塞给 LLM。"</b>
 * 那句话成立的前提是每一级都能真的收窄 —— 意图落到一个能力(个位数), 能力落到少数几个候选
 * 应用, 选中应用之后才看到它的 action。所以断言写成"{@code game.play} 下<em>至少</em>两个候选"
 * 与"{@code reminder.manage} 下<em>恰好</em>一个"。前者证明收窄不是靠"每个能力只有一个应用"
 * 这种巧合, 后者证明不同能力域真的是分开的。
 */
@ActiveProfiles("test")
@SpringBootTest
class CapabilityResolverTest {

    private static final String TICTACTOE = "com.luxera.tictactoe";
    private static final String GOMOKU = "com.luxera.gomoku";
    private static final String REMINDER = "com.luxera.reminder";

    @Autowired
    ActionGateway gateway;

    // ─────────────────────────── 能力目录 ───────────────────────────

    @Test
    void theCatalogueHoldsBothReferenceCapabilities() {
        List<String> ids = gateway.capabilities().stream().map(CapabilityView::capabilityId).toList();

        assertTrue(ids.contains("game.play"), "目录里应该有 game.play: " + ids);
        assertTrue(ids.contains("reminder.manage"), "目录里应该有 reminder.manage: " + ids);
    }

    /** 能力是<em>粗</em>的 —— 它是给 LLM 选的那一层, 所以它必须带着人话写的说明。 */
    @Test
    void everyCapabilityCarriesSomethingAHumanCouldRead() {
        for (CapabilityView capability : gateway.capabilities()) {
            assertTrue(capability.title() != null && !capability.title().isBlank(),
                    "能力必须有标题: " + capability.capabilityId());
            assertTrue(capability.description() != null && !capability.description().isBlank(),
                    "能力必须有描述, 否则 LLM 只能靠 id 猜: " + capability.capabilityId());
        }
    }

    // ─────────────────────────── 收窄 ───────────────────────────

    /** {@code game.play} 下有<em>至少两个</em>候选 —— 井字棋与五子棋。收窄是真的收窄, 不是唯一解。 */
    @Test
    void gamePlayHasMoreThanOneCandidate() {
        List<String> ids = candidateIds("game.play");

        assertTrue(ids.contains(TICTACTOE), "game.play 下应该有井字棋: " + ids);
        assertTrue(ids.contains(GOMOKU), "game.play 下应该有五子棋: " + ids);
        assertTrue(ids.size() >= 2, "同域多候选是发现链存在的理由: " + ids);
    }

    /** {@code reminder.manage} 下<em>恰好一个</em> —— 跨能力域时收窄一步到位。 */
    @Test
    void reminderManageHasExactlyOneCandidate() {
        assertEquals(List.of(REMINDER), candidateIds("reminder.manage"));
    }

    /** 两个能力域互不渗漏: 棋类不会出现在提醒的候选里, 提醒也不会出现在棋类的候选里。 */
    @Test
    void capabilitiesDoNotBleedIntoEachOther() {
        assertFalse(candidateIds("game.play").contains(REMINDER));
        assertFalse(candidateIds("reminder.manage").contains(TICTACTOE));
        assertFalse(candidateIds("reminder.manage").contains(GOMOKU));
    }

    @Test
    void anUnknownCapabilityHasNoCandidates() {
        assertEquals(List.of(), gateway.applicationsFor("nothing.like.this"));
        assertEquals(List.of(), gateway.applicationsFor(null));
        assertEquals(List.of(), gateway.applicationsFor("  "));
    }

    // ─────────────────────────── 候选的形状 ───────────────────────────

    /** 候选要自报家门到"这个应用能做什么", 否则第二级收窄还得回头再查一次目录。 */
    @Test
    void aCandidateDeclaresTheCapabilitiesItWasFoundBy() {
        for (ApplicationView candidate : gateway.applicationsFor("game.play")) {
            assertTrue(candidate.capabilities().contains("game.play"),
                    "候选应当声明自己是靠哪个能力被找到的: " + candidate);
            assertTrue(candidate.version() != null && !candidate.version().isBlank(),
                    "候选必须带版本 —— manifest 属于版本, 不认识版本就不知道它有哪些 action: " + candidate);
        }
    }

    /** 两个棋类应用虽然同域, 但必须是两个<em>不同的</em>应用, 不是一个应用的两个版本。 */
    @Test
    void theTwoGamesAreDistinctApplications() {
        List<ApplicationView> candidates = gateway.applicationsFor("game.play");

        long distinct = candidates.stream().map(ApplicationView::applicationId).distinct().count();
        assertEquals(candidates.size(), distinct, "候选里不该有重复的 applicationId: " + candidates);
    }

    private List<String> candidateIds(String capabilityId) {
        return gateway.applicationsFor(capabilityId).stream()
                .map(ApplicationView::applicationId)
                .toList();
    }
}
