package com.luxera.companion.client;

import org.springframework.http.HttpStatus;

/**
 * V2.2 §6.3 —— 客户端形态端点上的"这一次不行"。
 *
 * <h2>为什么它不是 {@code BusinessException}</h2>
 *
 * <p>{@link com.luxera.companion.common.BusinessException} 是**平台自己**的业务异常, 它的
 * 渲染器({@code GlobalExceptionHandler})回的是平台的 {@code ApiError} 形状。而
 * {@code /api/client/**} 是**给聊天软件用的公开面**: 它的调用方是一个客户端 SDK, 那里所有
 * 回答(成功与失败)都是 §6.3 那几个 record 的形状 —— 掺进第二种错误体, 等于让对面每写一个
 * 请求都要先判断"这是不是平台的内部错误"。
 *
 * <p>所以客户端面自己带一个异常类型, 由 {@link ClientApiExceptionHandler} 渲染成
 * {@code {error, code?, hint?}} —— 与对外开放面({@code ChatAccessController} 的
 * {@code {error, hint}})同一先例。附带的收益是 <b>401 / 403 / 404 / 409 的分野写在看得见的
 * 地方</b>: 每一个构造点都在说"这是调用方的问题还是状态的问题"。
 *
 * <h2>为什么带上 {@code code}</h2>
 *
 * <p>{@code error} 是给人看的一句话, 它会随文案调整而变; {@code code} 是给程序看的, 它不会。
 * 客户端要针对某一类失败做特殊处理(例如 {@code SESSION_EXPIRED} 之后自动重新 login)时,
 * 唯一能依赖的是 {@code code}。
 */
public class ClientApiException extends RuntimeException {

    /** 没带凭据 / 凭据无效 / 凭据过期 —— 三者对外都是这个, 换一个合法凭据再来。 */
    public static final String CODE_UNAUTHENTICATED = "CLIENT_UNAUTHENTICATED";
    /** 身份是合法的, 但做不了这件事(管理钥匙走客户端面、真人令牌想调 provision 之类)。 */
    public static final String CODE_FORBIDDEN = "CLIENT_FORBIDDEN";
    /** 这一段会话/这个账号对**我**不存在。见 {@link #notFound}: 不是"存在但不归你"。 */
    public static final String CODE_NOT_FOUND = "CLIENT_NOT_FOUND";
    /** 请求本身的形状不对(缺字段、limit 超界、cursor 解不开)。 */
    public static final String CODE_BAD_REQUEST = "CLIENT_BAD_REQUEST";
    /** 状态冲突: 例如"这个 Agent 还没有聊天账号, 先去 provision"。 */
    public static final String CODE_CONFLICT = "CLIENT_CONFLICT";

    private final HttpStatus status;
    private final String code;
    private final String hint;

    private ClientApiException(HttpStatus status, String code, String message, String hint) {
        super(message);
        this.status = status;
        this.code = code;
        this.hint = hint;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 给调用方的下一步 —— 可空。有它的时候, 失败信息里就带着"该怎么办"。 */
    public String hint() {
        return hint;
    }

    public static ClientApiException unauthenticated(String message, String hint) {
        return new ClientApiException(HttpStatus.UNAUTHORIZED, CODE_UNAUTHENTICATED, message, hint);
    }

    public static ClientApiException forbidden(String message, String hint) {
        return new ClientApiException(HttpStatus.FORBIDDEN, CODE_FORBIDDEN, message, hint);
    }

    /**
     * 「对我不存在」—— 用 404 而不是 403, 而且**不区分**"没有这个 id"与"有但不归我"。
     *
     * <p>403 等于告诉调用方"这个 id 是真实存在的, 只是不归你" —— 对一段他猜出来的会话 id
     * 来说, 那是一条他不该拿到的信息。对外开放面的越界处理是同一条规矩
     * ({@code ConversationService.requireOwnedByAgent})。
     */
    public static ClientApiException notFound(String message) {
        return new ClientApiException(HttpStatus.NOT_FOUND, CODE_NOT_FOUND, message, null);
    }

    public static ClientApiException badRequest(String message, String hint) {
        return new ClientApiException(HttpStatus.BAD_REQUEST, CODE_BAD_REQUEST, message, hint);
    }

    public static ClientApiException conflict(String message, String hint) {
        return new ClientApiException(HttpStatus.CONFLICT, CODE_CONFLICT, message, hint);
    }

    /**
     * 接入钥匙的失败 → HTTP, 状态码由<b>原因</b>决定而不是由调用方决定。
     *
     * <p>"钥匙无效"是调用方的问题(401, 换一把); "这个部署没配管理密钥"是运维的问题(503,
     * 没有人能换对钥匙)。把后者答成 401 会让接入方去翻自己的配置, 翻到天亮也翻不出结果 ——
     * 对外开放面({@code ChatAccessController.failure})立的就是这一条, 这里逐字沿用,
     * 免得同一个失败码在两个面上给出两种状态码。
     */
    public static ClientApiException fromAccessKeyFailure(
            com.luxera.companion.application.principal.PrincipalResolver.PrincipalException e) {
        HttpStatus status = com.luxera.companion.application.principal.ApiKeyPrincipalResolver
                .CODE_ADMIN_DISABLED.equals(e.code())
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.UNAUTHORIZED;
        return new ClientApiException(status, e.code(), e.getMessage(), null);
    }
}
