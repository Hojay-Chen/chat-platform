package com.luxera.companion.contracts.simulator;

import java.util.Set;

/**
 * 更新消息投递状态命令(V10 §4.3 ChangeDeviceStateCapability 的消息侧对应物)。
 *
 * 数字人"看到/读到/忽略"一条消息, 本质是它通过模拟客户端执行了客户端动作,
 * 因此必须建模为 Capability 而不是直接写 Chat 数据库。
 * status 取值见 MessageLifecycle: DELIVERED/NOTIFIED/NOTICED/CHECKED/READ/DEFERRED/IGNORED/RESPONDED。
 */
public record UpdateDeliveryStatusCommand(
        String commandId,
        String sessionId,
        Set<String> messageIds,
        String status
) implements CapabilityCommand {

    @Override
    public CapabilityType type() { return CapabilityType.UPDATE_DELIVERY_STATUS; }

    public static UpdateDeliveryStatusCommand of(String commandId, String sessionId,
                                                 Set<String> messageIds, String status) {
        return new UpdateDeliveryStatusCommand(commandId, sessionId, messageIds, status);
    }
}
