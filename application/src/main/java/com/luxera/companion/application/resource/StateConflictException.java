package com.luxera.companion.application.resource;

/**
 * LAP v1: 乐观并发失败 —— 我读到的资源版本已经不是当前版本了。
 *
 * <p>刻意做成<em>异常</em>而不是"返回 null 让调用方自己判": 从 {@code ResourceStore} 到
 * {@code ActionHandlerContext.write} 中间隔着应用作者写的代码, 一个返回值很容易被忽略
 * ("写失败了就当没写"), 而一次被忽略的落子冲突就是棋盘上少一个子。异常无法被静默忽略,
 * 网关把它翻成 {@code STATE_CONFLICT} 并把<em>当前</em>版本带回给调用方。
 *
 * <p>注意它承载了足够的上下文, 所以网关不需要再查一次库就能构造响应 —— 冲突路径上一个
 * 多余的查询就是一段可能读到"又变了"的窗口。
 */
public class StateConflictException extends RuntimeException {

    private final String uri;
    private final long expectedVersion;
    private final long currentVersion;
    private final String currentStateJson;
    private final String resourceType;
    private final String applicationId;
    private final String sessionId;

    public StateConflictException(String uri,
                                  long expectedVersion,
                                  long currentVersion,
                                  String currentStateJson,
                                  String resourceType,
                                  String applicationId,
                                  String sessionId) {
        super("STATE_CONFLICT: " + uri + " 期望版本 " + expectedVersion
                + ", 当前版本 " + currentVersion);
        this.uri = uri;
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
        this.currentStateJson = currentStateJson;
        this.resourceType = resourceType;
        this.applicationId = applicationId;
        this.sessionId = sessionId;
    }

    public String uri() {
        return uri;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }

    /** 当前状态原文; 调用方据此立刻重读重试, 而不是拿到失败就放弃。 */
    public String currentStateJson() {
        return currentStateJson;
    }

    public String resourceType() {
        return resourceType;
    }

    public String applicationId() {
        return applicationId;
    }

    public String sessionId() {
        return sessionId;
    }
}
