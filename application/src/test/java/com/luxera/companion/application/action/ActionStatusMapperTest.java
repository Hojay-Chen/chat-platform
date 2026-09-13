package com.luxera.companion.application.action;

import com.luxera.companion.contracts.application.ActionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 那张 {@code ActionStatus → HTTP} 的表逐行走一遍。
 *
 * <p>为什么值得一个专门的测试类: REST 控制器、MCP 适配器、{@code ApplicationRuntimePort}
 * 三处共用它, 任何一处自己 switch 都会让同一个失败在三个界面上说三种话。这张表是<em>唯一</em>
 * 的映射, 于是它必须被逐格钉住 —— 包括"枚举加了新值而没人决定它该是什么状态码"这一种。
 */
class ActionStatusMapperTest {

    @Test
    void mapsEveryStatusInTheTable() {
        Map<ActionStatus, HttpStatus> table = new EnumMap<>(ActionStatus.class);
        table.put(ActionStatus.SUCCESS, HttpStatus.OK);
        table.put(ActionStatus.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST);
        table.put(ActionStatus.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
        table.put(ActionStatus.DENIED, HttpStatus.FORBIDDEN);
        table.put(ActionStatus.NOT_FOUND, HttpStatus.NOT_FOUND);
        table.put(ActionStatus.STATE_CONFLICT, HttpStatus.CONFLICT);
        table.put(ActionStatus.IDEMPOTENCY_IN_PROGRESS, HttpStatus.CONFLICT);
        table.put(ActionStatus.IDEMPOTENCY_KEY_REUSED, HttpStatus.UNPROCESSABLE_ENTITY);
        table.put(ActionStatus.EXPIRED, HttpStatus.CONFLICT);
        table.put(ActionStatus.REQUIRE_CONFIRMATION, HttpStatus.PRECONDITION_REQUIRED);
        table.put(ActionStatus.FAILED, HttpStatus.INTERNAL_SERVER_ERROR);

        // 枚举里多了值而没在这张表里决定格子 → 这条先失败, 而不是线上返回 500
        assertEquals(ActionStatus.values().length, table.size(),
                "ActionStatus 有值没有对应的 HTTP 映射");
        table.forEach((status, http) ->
                assertEquals(http, ActionStatusMapper.httpStatus(status), status.name()));
    }

    /**
     * {@code DENIED} 是 403 而不是 401: 身份是有效的, 只是不被允许。
     * 401 会把客户端推去重新登录 —— 一个正好错误的动作。
     */
    @Test
    void deniedIsForbiddenNotUnauthorized() {
        assertEquals(HttpStatus.FORBIDDEN, ActionStatusMapper.httpStatus(ActionStatus.DENIED));
    }

    @Test
    void nullStatusIsServerErrorRatherThanSuccess() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ActionStatusMapper.httpStatus(null));
    }

    @Test
    void onlySuccessIsNotAnError() {
        assertFalse(ActionStatusMapper.isError(ActionStatus.SUCCESS));
        for (ActionStatus status : ActionStatus.values()) {
            if (status != ActionStatus.SUCCESS) {
                assertTrue(ActionStatusMapper.isError(status), status.name());
            }
        }
    }
}
