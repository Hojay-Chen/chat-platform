package com.luxera.companion.contracts.simulator;

/**
 * 列出会话命令(conversation.list scope)。
 */
public record ListConversationsCommand(
        String commandId,
        String sessionId,
        String userId,
        String companionId
) implements CapabilityCommand {

    @Override
    public CapabilityType type() { return CapabilityType.LIST_CONVERSATIONS; }

    public static ListConversationsCommand of(String commandId, String sessionId, String userId) {
        return new ListConversationsCommand(commandId, sessionId, userId, null);
    }

    public static ListConversationsCommand of(String commandId, String sessionId, String userId, String companionId) {
        return new ListConversationsCommand(commandId, sessionId, userId, companionId);
    }
}
