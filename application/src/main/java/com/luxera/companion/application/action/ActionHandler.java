package com.luxera.companion.application.action;

/**
 * 一个 LAP 动作的实现。用 {@link ActionHandlerKey} 注册 —— 从 R4 起,
 * {@code (applicationId, version, actionId)} 三元组是唯一的注册身份。
 *
 * <p>"加一个新应用 = 写一个 manifest + 一组 handler, 不改数字人一行代码" 这句话,
 * 落地就是本接口: handler 只看得见契约类型({@code ActionRequest} / {@code ResourceView} /
 * {@code InvocationContext}), 看不见任何一个具体应用。
 */
@FunctionalInterface
public interface ActionHandler {

    ActionOutcome execute(ActionHandlerContext context);
}
