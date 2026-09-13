package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.luxera.companion.contracts.application.ActionStatus;

/**
 * 处理器执行一个动作之后交给网关的东西。比 {@code ActionResponse} 少一层:
 * 处理器不知道也不该知道幂等重放、资源版本回填这些事 —— 那是网关的活。
 */
public record ActionOutcome(
        ActionStatus status,
        JsonNode result,
        String errorCode,
        String errorMessage
) {

    public ActionOutcome {
        if (status == null) status = ActionStatus.FAILED;
        if (result != null && result.isNull()) result = null;
    }

    public boolean succeeded() {
        return status == ActionStatus.SUCCESS;
    }

    public static ActionOutcome success(JsonNode result) {
        return new ActionOutcome(ActionStatus.SUCCESS, result, null, null);
    }

    public static ActionOutcome fail(String code, String message) {
        return new ActionOutcome(ActionStatus.FAILED, NullNode.getInstance(), code, message);
    }

    public static ActionOutcome of(ActionStatus status, String code, String message) {
        return new ActionOutcome(status, NullNode.getInstance(), code, message);
    }
}
