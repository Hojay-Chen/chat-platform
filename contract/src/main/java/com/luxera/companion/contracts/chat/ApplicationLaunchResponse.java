package com.luxera.companion.contracts.chat;

/**
 * LAP v2 §64: <b>打开成功了 —— 这是这一场, 以及你在这一场里是谁</b>。
 *
 * <p>两个字段, 不是六个。{@code session} 回答"开出来的是哪一个实例", {@code participant}
 * 回答"你在这个实例里的位置"。这两件事必须一起给出: 只知道会话 id 而不知道自己是谁的调用方,
 * 下一步一定会去猜自己的 role, 而猜出来的 role 会决定它渲染什么按钮 —— 一个猜错的角色
 * 会让界面长出一个按下去 403 的按钮。
 *
 * <p><b>刻意不给 {@code messageId}。</b> 那个字段属于聊天平台, 不属于这个契约 ——
 * 应用平台不知道"打开应用"还要在对话里落一条消息(那是聊天平台的编排决定)。聊天端点
 * 在自己的响应里把它加上, 而这里保持两个平台各自只说自己知道的事。
 *
 * @param session     开出来的会话
 * @param participant 调用方在这一场里的身份
 */
public record ApplicationLaunchResponse(ApplicationSessionView session, ParticipantView participant) {}
