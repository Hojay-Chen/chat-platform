package com.luxera.companion.contracts.simulator;

/**
 * 发送消息命令(V10 §4.3 SendMessageCapability)。
 *
 * clientMessageId 作为 Chat 侧幂等键: 同 commandId 重试不会重复发送。
 */
public record SendMessageCommand(
        String commandId,
        String sessionId,
        String conversationId,
        String senderType,
        String content,
        String messageKind,
        String clientMessageId
) implements CapabilityCommand {

    @Override
    public CapabilityType type() { return CapabilityType.SEND_MESSAGE; }

    public static SendMessageCommand of(String commandId, String sessionId, String conversationId,
                                        String senderType, String content, String messageKind) {
        return new SendMessageCommand(commandId, sessionId, conversationId, senderType,
                content, messageKind, null);
    }
}
