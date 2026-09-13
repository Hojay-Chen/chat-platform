package com.luxera.companion.simulator.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.contracts.dhcp.CommandMessage;
import com.luxera.companion.contracts.dhcp.CommandResultMessage;
import com.luxera.companion.contracts.dhcp.DhcpConstants;
import com.luxera.companion.contracts.dhcp.DhcpError;
import com.luxera.companion.contracts.dhcp.DhcpErrorCode;
import com.luxera.companion.contracts.dhcp.DhcpFrame;
import com.luxera.companion.contracts.dhcp.DhcpFrameType;
import com.luxera.companion.contracts.simulator.CapabilityType;
import com.luxera.companion.contracts.simulator.ListConversationsCommand;
import com.luxera.companion.contracts.simulator.ReadMessagesCommand;
import com.luxera.companion.contracts.simulator.SendMessageCommand;
import com.luxera.companion.simulator.SimulatorSession;
import com.luxera.companion.simulator.SimulatorSessionRegistry;
import com.luxera.companion.contracts.simulator.UpdateDeliveryStatusCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §63 Server 端 COMMAND 分发器: 将 COMMAND 帧转换为具体的 SimulatorCapability 调用。
 *
 * 所有 command 的处理都走 SimulatorCapability 接口(现有 in-process 实现), 这样
 * chat 端与 DH 端的同步方式在本过渡期是一样的 — 只是传输协议从直接方法调用
 * 变成了 WebSocket 帧。R3 会把 Capability 内部改为走 WS 客户端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulatorCommandDispatcher {

    private final ConversationService conversationService;
    private final SimulatorSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 处理 COMMAND 帧, 返回 COMMAND_RESULT */
    public void handle(SimulatorConnection conn, DhcpFrame frame) {
        String requestId = frame.requestId();
        CommandMessage cmd = toCommandMessage(frame.payload());
        if (cmd == null) {
            sendResult(conn, requestId, false, null, null,
                    DhcpError.of(DhcpErrorCode.COMMAND_FAILED, "COMMAND payload 解析失败"));
            return;
        }

        // 1. 基础校验
        if (!conn.authed) {
            sendResult(conn, requestId, false, cmd.command(), cmd.idempotencyKey(),
                    DhcpError.of(DhcpErrorCode.AUTH_FAILED, "未完成 AUTH"));
            return;
        }
        SimulatorSession session = sessionRegistry.require(conn.accountId);
        if (session == null) {
            sendResult(conn, requestId, false, cmd.command(), cmd.idempotencyKey(),
                    DhcpError.of(DhcpErrorCode.SESSION_NOT_FOUND, "会话不存在: " + conn.accountId));
            return;
        }
        if (session.connectionState() != SimulatorSession.ConnectionState.CONNECTED) {
            sendResult(conn, requestId, false, cmd.command(), cmd.idempotencyKey(),
                    DhcpError.of(DhcpErrorCode.COMMAND_FAILED, "会话未连接: " + session.connectionState()));
            return;
        }

        // 2. scope 校验
        CapabilityType requiredScope = commandToScope(cmd.command());
        if (requiredScope != null && !session.can(requiredScope)) {
            sendResult(conn, requestId, false, cmd.command(), cmd.idempotencyKey(),
                    DhcpError.of(DhcpErrorCode.SCOPE_DENIED, "缺少 scope: " + requiredScope));
            return;
        }

        // 3. 分发到对应 Capability
        try {
            JsonNode resultData = executeCommand(cmd, conn);
            sendResult(conn, requestId, true, cmd.command(), cmd.idempotencyKey(), resultData);
        } catch (Exception e) {
            log.warn("[WS命令] {} 失败: {}", cmd.command(), e.getMessage());
            sendResult(conn, requestId, false, cmd.command(), cmd.idempotencyKey(),
                    DhcpError.of(DhcpErrorCode.COMMAND_FAILED, e.getMessage()));
        }
    }

    private CapabilityType commandToScope(String command) {
        return switch (command) {
            case "chat.sendMessage" -> CapabilityType.SEND_MESSAGE;
            case "chat.readMessages" -> CapabilityType.READ_MESSAGES;
            case "chat.listConversations" -> CapabilityType.LIST_CONVERSATIONS;
            case "chat.updateDeliveryStatus" -> CapabilityType.UPDATE_DELIVERY_STATUS;
            default -> null;
        };
    }

    private CommandMessage toCommandMessage(JsonNode payload) {
        try {
            String command = payload.get("command").asText();
            String idempotencyKey = payload.has("idempotencyKey") ? payload.get("idempotencyKey").asText() : null;
            JsonNode args = payload.has("args") ? payload.get("args") : null;
            return new CommandMessage(command, idempotencyKey, args);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode executeCommand(CommandMessage cmd, SimulatorConnection conn) {
        return switch (cmd.command()) {
            case "chat.sendMessage" -> sendMessage(cmd, conn);
            case "chat.readMessages" -> readMessages(cmd, conn);
            case "chat.listConversations" -> listConversations(cmd, conn);
            case "chat.updateDeliveryStatus" -> updateDeliveryStatus(cmd, conn);
            default -> throw new IllegalArgumentException("未知命令: " + cmd.command());
        };
    }

    private JsonNode sendMessage(CommandMessage cmd, SimulatorConnection conn) {
        SendMessageCommand sc = objectMapper.convertValue(cmd.args(), SendMessageCommand.class);
        if (sc == null) throw new IllegalArgumentException("SEND_MESSAGE args 为空");
        String convId = sc.conversationId();
        String senderType = sc.senderType();
        String content = sc.content();
        String messageKind = sc.messageKind();
        String idempotencyKey = sc.clientMessageId();
        var m = conversationService.addMessage(convId, senderType, content,
                null, null, null, false, messageKind, null, null, idempotencyKey);
        var node = objectMapper.createObjectNode()
                .put("messageId", m.getId())
                .put("conversationId", m.getConversationId())
                .put("senderType", m.getSenderType())
                .put("createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString());
        return node;
    }

    private JsonNode readMessages(CommandMessage cmd, SimulatorConnection conn) {
        ReadMessagesCommand rc = objectMapper.convertValue(cmd.args(), ReadMessagesCommand.class);
        if (rc == null) throw new IllegalArgumentException("READ_MESSAGES args 为空");
        List<Message> messages = rc.limit() > 0
                ? conversationService.recentMessages(rc.conversationId(), rc.limit())
                : conversationService.messages(rc.conversationId());
        var arr = objectMapper.createArrayNode();
        for (Message m : messages) {
            var node = objectMapper.createObjectNode()
                    .put("id", m.getId())
                    .put("conversationId", m.getConversationId())
                    .put("senderType", m.getSenderType())
                    .put("content", m.getContent())
                    .put("deliveryStatus", m.getDeliveryStatus())
                    .put("createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString())
                    .put("messageKind", m.getMessageKind())
                    .put("sessionId", m.getSessionId())
                    .put("exchangeId", m.getExchangeId());
            arr.add(node);
        }
        var node = objectMapper.createObjectNode()
                .put("count", messages.size())
                .set("messages", arr);
        return node;
    }

    private JsonNode listConversations(CommandMessage cmd, SimulatorConnection conn) {
        ListConversationsCommand lc = objectMapper.convertValue(cmd.args(), ListConversationsCommand.class);
        if (lc == null) throw new IllegalArgumentException("LIST_CONVERSATIONS args 为空");
        var conversations = conversationService.list(conn.accountId, lc.companionId());
        var arr = objectMapper.createArrayNode();
        for (var c : conversations) {
            var node = objectMapper.createObjectNode()
                    .put("id", c.getId())
                    .put("title", c.getTitle())
                    .put("lastMessageAt", c.getLastMessageAt() == null ? "" : c.getLastMessageAt().toString())
                    .put("messageCount", c.getMessageCount())
                    .put("unread", false);
            arr.add(node);
        }
        var node = objectMapper.createObjectNode().set("conversations", arr);
        return node;
    }

    private JsonNode updateDeliveryStatus(CommandMessage cmd, SimulatorConnection conn) {
        UpdateDeliveryStatusCommand uc = objectMapper.convertValue(cmd.args(), UpdateDeliveryStatusCommand.class);
        if (uc == null) throw new IllegalArgumentException("UPDATE_DELIVERY_STATUS args 为空");
        int updated = 0;
        for (String msgId : uc.messageIds()) {
            conversationService.updateDeliveryStatus(msgId, uc.status());
            updated++;
        }
        var node = objectMapper.createObjectNode()
                .put("updated", updated);
        return node;
    }

    /** 发送成功结果(data 为业务 JSON) */
    private void sendResult(SimulatorConnection conn, String requestId, boolean ok,
                            String command, String idempotencyKey, JsonNode data) {
        CommandResultMessage frame = new CommandResultMessage(ok, command, idempotencyKey, data, null);
        send(conn, requestId, frame);
    }

    /** 发送失败结果(payload 为 DhcpError) */
    private void sendResult(SimulatorConnection conn, String requestId, boolean ok,
                            String command, String idempotencyKey, DhcpError error) {
        CommandResultMessage frame = new CommandResultMessage(ok, command, idempotencyKey, null, error);
        send(conn, requestId, frame);
    }

    private void send(SimulatorConnection conn, String requestId, CommandResultMessage result) {
        var node = objectMapper.valueToTree(result);
        SimulatorWebSocketController.sendToDevice(conn.deviceId,
                DhcpFrame.of(DhcpFrameType.COMMAND_RESULT, requestId, null, node));
    }
}