package com.luxera.companion.application.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册键必须是 {@code (applicationId, version, actionId)}。
 *
 * <p>这一个测试类守着一件很容易做错、且错了以后很难发现的事: 用裸 {@code actionId} 当键的话,
 * 井字棋与五子棋的 {@code game.make_move} 会互相覆盖 —— 五子棋后注册, 于是井字棋走的是五子棋的
 * 棋盘, 而且<b>一句报错都没有</b>。五子棋那一轮(R5)才会加进来, 所以这条断言必须现在就写:
 * 它是"两个同域应用共存"这件事唯一的结构性保险。
 */
class ActionHandlerRegistryTest {

    private static final ActionHandler NOOP = ctx -> ActionOutcome.success(null);

    @Test
    void sameActionIdInTwoApplicationsDoesNotCollide() {
        ActionHandlerRegistry registry = new ActionHandlerRegistry();
        registry.register(ActionHandlerKey.of("com.luxera.tictactoe", "1.0.0", "game.make_move"), NOOP);
        registry.register(ActionHandlerKey.of("com.luxera.gomoku", "1.0.0", "game.make_move"), NOOP);

        assertEquals(2, registry.size(), "两个应用的同名动作必须共存");
        assertTrue(registry.find("com.luxera.tictactoe", "1.0.0", "game.make_move").isPresent());
        assertTrue(registry.find("com.luxera.gomoku", "1.0.0", "game.make_move").isPresent());
    }

    @Test
    void versionIsPartOfTheKey() {
        ActionHandlerRegistry registry = new ActionHandlerRegistry();
        registry.register(ActionHandlerKey.of("com.luxera.tictactoe", "1.0.0", "game.make_move"), NOOP);
        registry.register(ActionHandlerKey.of("com.luxera.tictactoe", "1.0.1", "game.make_move"), NOOP);

        assertEquals(2, registry.size());
        assertTrue(registry.contains("com.luxera.tictactoe", "1.0.0", "game.make_move"));
        assertTrue(registry.contains("com.luxera.tictactoe", "1.0.1", "game.make_move"));
    }

    /** 重复注册是编程错误: 抛异常, 不静默覆盖 —— 启动时就炸, 而不是运行期跑错代码。 */
    @Test
    void duplicateRegistrationThrowsInsteadOfOverwriting() {
        ActionHandlerRegistry registry = new ActionHandlerRegistry();
        ActionHandlerKey key = ActionHandlerKey.of("com.luxera.tictactoe", "1.0.0", "game.make_move");
        registry.register(key, NOOP);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.register(key, ctx -> ActionOutcome.fail("OTHER", "另一个实现")));
        assertTrue(e.getMessage().contains("com.luxera.tictactoe/1.0.0/game.make_move"),
                "异常里要能看到是哪把键: " + e.getMessage());
    }

    @Test
    void findToleratesNullAndUnknownKeys() {
        ActionHandlerRegistry registry = new ActionHandlerRegistry();
        assertTrue(registry.find((ActionHandlerKey) null).isEmpty());
        assertTrue(registry.find(null, null, null).isEmpty());
        assertTrue(registry.find("com.luxera.nope", "9.9.9", "game.make_move").isEmpty());
    }

    /** 网关拿到的本来就是一把键({@code ActionResolution.handlerKey()}), 不该拆开再拼回去。 */
    @Test
    void findAcceptsTheKeyTheGatewayAlreadyHas() {
        ActionHandlerRegistry registry = new ActionHandlerRegistry();
        registry.register(ActionHandlerKey.of("com.luxera.tictactoe", "1.0.0", "game.state"), NOOP);

        assertTrue(registry.find(ActionHandlerKey.of("com.luxera.tictactoe", "1.0.0", "game.state"))
                .isPresent());
    }
}
