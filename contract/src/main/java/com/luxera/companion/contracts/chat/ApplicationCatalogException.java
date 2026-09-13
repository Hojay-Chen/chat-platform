package com.luxera.companion.contracts.chat;

import com.luxera.companion.contracts.application.ActionStatus;

/**
 * LAP v2 §64: <b>应用平台对聊天平台说"不行"</b>。
 *
 * <p>这不是第七个 DTO, 它是那几个 DTO 的<em>失败面</em> —— 没有它, 边界就只能靠
 * {@code RuntimeException} 把话说出去, 于是聊天侧对一个"会话满员"和一个"应用被下架"
 * 只能给出同一个反应。
 *
 * <h2>为什么必须有一个跨模块的异常类型, 而不是让聊天侧 catch 应用平台的异常</h2>
 * <p>因为那需要 chat-platform 在编译期看得见 {@code SessionException}, 也就是看得见
 * application-platform —— 而那条依赖正是 {@code ModuleBoundaryArchitectureTest} 与
 * {@code check-v10.sh} 明令禁止的。异常类型和 DTO 一样, 是边界的一部分:
 * <em>边界上流过的每一种类型的类, 都必须住在边界本身。</em>
 *
 * <h2>状态用 {@link ActionStatus}, 不另立一套</h2>
 * <p>平台里已经有一个"为什么被拒"的封闭词汇表了 —— 动作网关用它, 会话归属链用它
 * ({@code SessionException} 就带着它)。为一个新端口发明第二套四值枚举, 就是 §53 警告的
 * "平行系统"的一个小小版本: 它今天只是四个名字, 明天就会有人只给其中一套加一个值。
 * 所以这里直接复用: 应用平台侧抛出的状态由 {@code SessionException} 原样带过来。
 *
 * <p>{@code code} 仍然是字符串, 而且刻意保持着应用平台里的原样
 * ({@code NOT_A_PARTICIPANT} / {@code SESSION_FULL} / {@code APPLICATION_NOT_AVAILABLE} …)。
 * 客户端认的是它 —— 把它在这里翻译一遍, 就等于把一张错误码表抄成两份。
 */
public class ApplicationCatalogException extends RuntimeException {

    private final String code;
    private final ActionStatus status;

    public ApplicationCatalogException(String code, String message, ActionStatus status) {
        super(message);
        this.code = code;
        this.status = status == null ? ActionStatus.INVALID_ARGUMENT : status;
    }

    public String code() {
        return code;
    }

    /**
     * 被拒的类别。聊天侧用它决定 HTTP 状态; <b>不需要, 也不该</b>, 去认每一个具体的 code。
     */
    public ActionStatus status() {
        return status;
    }
}
