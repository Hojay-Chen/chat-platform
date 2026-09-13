package com.luxera.companion.application.action;

/**
 * LAP v1: 一个应用版本的二元组身份。
 *
 * <p>和 {@link ActionHandlerKey} 的区别是用途: 那个是"哪一个动作的处理器", 这个是
 * "哪一个应用的这一类回调"。应用级的回调(如"此刻哪些动作可用")每个版本只有一份,
 * 用这个键注册; 动作级的处理器每个动作一份, 用那个键注册。
 *
 * <p>两个键都必须带 version。只按 applicationId 索引的注册表在第一次发新版本时就会
 * 悄悄让老会话跑新代码 —— 而 {@code action_invocation} 里记着老版本, 于是"这次调用跑的是
 * 哪一版"永远对不上。
 */
public record ApplicationKey(String applicationId, String version) {

    public static ApplicationKey of(String applicationId, String version) {
        return new ApplicationKey(applicationId, version);
    }

    @Override
    public String toString() {
        return applicationId + "@" + version;
    }
}
