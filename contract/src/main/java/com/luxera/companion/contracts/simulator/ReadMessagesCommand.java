package com.luxera.companion.contracts.simulator;

/**
 * 读取会话消息命令(V10 §4.3 ReadMessageCapability)。
 * limit <= 0 表示读取全部。
 */
public record ReadMessagesCommand(
        String commandId,
        String sessionId,
        String conversationId,
        int limit
) implements CapabilityCommand {

    @Override
    public CapabilityType type() { return CapabilityType.READ_MESSAGES; }

    public static ReadMessagesCommand recent(String commandId, String sessionId, String conversationId, int limit) {
        return new ReadMessagesCommand(commandId, sessionId, conversationId, limit);
    }
}
