package com.luxera.companion.contracts.simulator;

/**
 * V10 §5.2 Simulator 能力类型(Scope)。
 *
 * 每个 Capability 对应一个最小权限单元; Simulator Session 只被授予
 * 显式声明的 scopes, 禁止默认万能权限。
 */
public enum CapabilityType {

    /** 读取会话消息(chat.read) */
    READ_MESSAGES,

    /** 发送消息(chat.send) */
    SEND_MESSAGE,

    /** 列出会话(conversation.list) */
    LIST_CONVERSATIONS,

    /** 更新消息投递状态: 通知/注意到/已读/忽略(delivery.update) */
    UPDATE_DELIVERY_STATUS
}
