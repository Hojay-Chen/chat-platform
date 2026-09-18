package com.luxera.companion.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.persistence.EntityNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /api/client/**} 上的失败 → HTTP, <b>形状与成功响应同一族</b>。
 *
 * <h2>为什么它必须抢在 {@code GlobalExceptionHandler} 前面</h2>
 *
 * <p>那一个是仓库里的兜底 advice, 它把任何未处理的异常渲染成平台的 {@code ApiError}
 * ({@code {error, hint}})。形状看着很像, 但它**没有 {@code code} 字段**, 而客户端要靠
 * {@code code} 决定"要不要重新 login" —— 少了它, 对面只能去匹配 {@code error} 里那句会随
 * 文案调整而变的中文。所以这里 {@code @Order(HIGHEST_PRECEDENCE)} + 用
 * {@code assignableTypes} 把范围钉死在 {@link ClientApiController} 上。
 *
 * <p>{@code assignableTypes} 是这里最要紧的一个词: 没有它, 这个 advice 会接住**全平台**的
 * 异常(它是 HIGHEST_PRECEDENCE 的), 于是 {@code /api/conversations} 那边的一个 404 会突然
 * 变成客户端面的错误体 —— 而那是一个前端已经在解析的形状。
 *
 * <h2>它接住三类东西</h2>
 *
 * <ol>
 *   <li>{@link ClientApiException} —— 我们自己抛的, 状态码与 code 都在它身上, 这里只负责渲染。</li>
 *   <li>{@link EntityNotFoundException} —— 与 {@code ConversationService.requireOwnedByAgent}
 *       同一个先例: 越界答 404, 而且与"这个 id 不存在"分不出来。</li>
 *   <li>{@link IllegalArgumentException} —— 参数不对(空内容、账号与伴侣对不上之类)。
 *       400 而不是 500: 那是调用方给的请求有问题。</li>
 * </ol>
 *
 * <p>{@code NoHandlerFoundException} 也接着, 但它接的理由不同: {@code /api/client/typo} 这种
 * 路径如果落到兜底 advice 上, 会得到一个 500(那个 advice 的 {@code Exception} 分支)——
 * 而"没有这个路径"明明是一个 404, 且它最容易在下一次改接口时被误诊成"服务器挂了"。
 */
@Slf4j
@RestControllerAdvice(assignableTypes = ClientApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientApiExceptionHandler {

    @ExceptionHandler(ClientApiException.class)
    public ResponseEntity<Map<String, Object>> handleClient(ClientApiException e) {
        return ResponseEntity.status(e.status()).body(error(e.getMessage(), e.code(), e.hint()));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(e.getMessage(), ClientApiException.CODE_NOT_FOUND, null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(error(e.getMessage(), ClientApiException.CODE_BAD_REQUEST, null));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandler(NoHandlerFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("没有这个端点: " + e.getRequestURL(),
                        ClientApiException.CODE_NOT_FOUND, null));
    }

    /**
     * 兜底 —— 留一条日志, 对外只说"服务器出错了"。
     *
     * <p>刻意<b>不回</b> {@code e.getMessage()}: 它常常带着堆栈里的类名与库里的字段名,
     * 而这是开放给第三方程序的面。真正的原因在日志里。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("[客户端面] 未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("服务器内部错误", null, null));
    }

    /** 顺序在这里写死: {@code error} 放人话、{@code code} 放机器码、{@code hint} 放下一步。 */
    private static Map<String, Object> error(String message, String code, String hint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null ? "请求失败" : message);
        if (code != null) body.put("code", code);
        if (hint != null) body.put("hint", hint);
        return body;
    }
}
