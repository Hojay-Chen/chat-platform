package com.luxera.companion.conversation;

import com.luxera.companion.contracts.chat.ApplicationCatalogException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LAP v2 §64: <b>应用平台说"不行"时, 聊天端点该回什么</b>。
 *
 * <h2>为什么这里有一份 {@code ActionStatus → HTTP} 的映射, 而应用平台里也有一份</h2>
 * <p>因为两条边界<em>不能共用代码</em>: 应用平台那份(它的 {@code ActionStatusMapper})
 * 住在应用平台自己的包里, 而 chat-platform 在编译期看不见那个包 —— 这正是
 * {@code ApplicationCatalogPort} 存在的理由。把那张表搬进 {@code contracts} 也不行:
 * 它要 {@code org.springframework.http.HttpStatus}, 而 {@code contracts} 是一个
 * 不依赖 Spring 的纯类型模块。
 *
 * <p>于是这里有一份刻意的、四行的重复。<b>它值得被明确写下来, 而不是假装没有</b> ——
 * 一份不被承认的重复会慢慢长歪, 一份被承认的重复至少会有人在改其中一份时想起来看看另一份。
 * 重复的范围也被刻意压到最小: 只覆盖 {@link ApplicationCatalogException} 里那一个
 * {@code ActionStatus}, 不复制整张表(那份表里一半的值在这个端口上不可能出现)。
 *
 * <h2>403 而不是 401</h2>
 * <p>调用方身份是有效的(它已经通过了聊天平台的登录), 只是不被允许做这件事。401 会让客户端
 * 去重新登录 —— 一个正好错误的动作。这条与应用平台的判断一致。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConversationApplicationExceptionHandler {

    @ExceptionHandler(ApplicationCatalogException.class)
    public ResponseEntity<Map<String, Object>> handleCatalog(ApplicationCatalogException e) {
        HttpStatus status = switch (e.status()) {
            case DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STATE_CONFLICT, EXPIRED -> HttpStatus.CONFLICT;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            // 端口上其余的状态(幂等、确认、失败)不该从这条路出来 —— 出来了就说明
            // 应用平台那边多了一条这条边界没定义过的失败。当 500 并留下痕迹, 比猜一个
            // 4xx 让它悄悄消失在客户端要好。
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.warn("[ConversationApplication] 端口回了一个这条边界没定义的失败: {} ({})",
                    e.code(), e.status());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", e.status().name());
        body.put("code", e.code());
        body.put("message", e.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
