/**
 * LAP v2 §64: <b>聊天平台看得见的那部分应用生态</b>。
 *
 * <p>这个包存在的理由是一条边界, 不是一组便利类型:
 *
 * <pre>
 *   chat-platform  ──只依赖──▶  contracts.chat + contracts.spi.ApplicationCatalogPort
 *                                        ▲ 实现
 *                              application-platform
 * </pre>
 *
 * <p>§63 要聊天平台能发现应用、启动应用、邀请他人; §115 要它不 import
 * {@code application.builtin.*}。而本仓库既有的守卫更强 ——
 * {@code ModuleBoundaryArchitectureTest.chat_platform_does_not_depend_on_application_platform}
 * 与 {@code check-v10.sh} 的 pom 禁令要求 chat-platform 与 application-platform 之间
 * <em>完全没有</em>依赖。三者同时成立的唯一办法就是这里: 一个契约端口
 * ({@link com.luxera.companion.contracts.spi.ApplicationCatalogPort})加上一组
 * 不携带任何平台知识的 DTO。
 *
 * <p>放宽边界换功能是最贵的偷懒: 一旦 chat-platform 能 import application-platform,
 * 下一个"顺手"就会是它去 import 一个具体的应用, 而 §63 那条"聊天平台不知道任何一个
 * 具体应用"的验收也就没有东西再守着它了。
 *
 * <h2>§64 列了七个名字, 这里是六个</h2>
 * <p>第七个是 {@code ApplicationEventView} —— 它<b>没有</b>在这里另立一个类型, 因为
 * {@link com.luxera.companion.contracts.application.ApplicationEvent} 已经就是它:
 * §53 明令"一套事件词汇, 不许有第七个字段之外的平行事件系统", 为聊天语境再包一层
 * 视图类型, 正是那句话要防的东西的一个温和版本 —— 它今天字段相同, 明天就会有人只给
 * 聊天视图加一个字段。
 *
 * <p>所以聊天侧拿到的应用事件就是 {@code ApplicationEvent} 本身, 没有翻译层。
 */
package com.luxera.companion.contracts.chat;
