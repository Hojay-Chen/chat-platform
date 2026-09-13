package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §62/§63 DHCP 协议帧序列化契约测试:
 * 两端(chat-server / DH-connector)各自独立反序列化同一 JSON 必须得到等价帧。
 */
class DhcpFrameProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void frameRoundTrip_withPayload() throws Exception {
        JsonNode payload = mapper.createObjectNode()
                .put("deviceId", "sim-dev-1")
                .put("accessToken", "tok-abc");
        DhcpFrame original = DhcpFrame.of(DhcpFrameType.AUTH, "req-1", 7L, payload);

        String json = mapper.writeValueAsString(original);
        DhcpFrame parsed = mapper.readValue(json, DhcpFrame.class);

        assertEquals(DhcpFrameType.AUTH, parsed.type());
        assertEquals("req-1", parsed.requestId());
        assertEquals(7L, parsed.sequence());
        assertEquals(DhcpConstants.PROTOCOL_VERSION, parsed.version());
        assertEquals("sim-dev-1", parsed.payload().get("deviceId").asText());
    }

    @Test
    void frameWithoutSequence_roundTrips() throws Exception {
        DhcpFrame original = DhcpFrame.of(DhcpFrameType.PING, "ping-1", null,
                mapper.createObjectNode().put("t", 1));
        DhcpFrame parsed = mapper.readValue(mapper.writeValueAsString(original), DhcpFrame.class);
        assertNull(parsed.sequence());
        assertEquals(DhcpFrameType.PING, parsed.type());
    }

    @Test
    void allFrameTypes_serialize() throws Exception {
        for (DhcpFrameType type : DhcpFrameType.values()) {
            DhcpFrame frame = DhcpFrame.of(type, "req-x", null, null);
            DhcpFrame parsed = mapper.readValue(mapper.writeValueAsString(frame), DhcpFrame.class);
            assertEquals(type, parsed.type());
        }
    }

    @Test
    void connectAndAuthAndSubscribe_payloads() throws Exception {
        // CONNECT
        ConnectMessage connect = ConnectMessage.v1("dh-connector/1.0");
        assertEquals(Set.of("dhcp.v1"), connect.supportedVersions());
        DhcpFrame f1 = DhcpFrame.of(DhcpFrameType.CONNECT, "c1", mapper.valueToTree(connect));
        ConnectMessage c2 = mapper.convertValue(f1.payload(), ConnectMessage.class);
        assertEquals(connect.supportedVersions(), c2.supportedVersions());

        // AUTH
        AuthMessage auth = new AuthMessage("dev-1", "tok-1");
        DhcpFrame f2 = DhcpFrame.of(DhcpFrameType.AUTH, "a1", mapper.valueToTree(auth));
        AuthMessage a2 = mapper.convertValue(f2.payload(), AuthMessage.class);
        assertEquals("dev-1", a2.deviceId());
        assertEquals("tok-1", a2.accessToken());

        // SUBSCRIBE
        SubscribeMessage sub = SubscribeMessage.of(List.of("chat.message.*", "phone.notification.*"));
        DhcpFrame f3 = DhcpFrame.of(DhcpFrameType.SUBSCRIBE, "s1", mapper.valueToTree(sub));
        SubscribeMessage s2 = mapper.convertValue(f3.payload(), SubscribeMessage.class);
        assertEquals(List.of("chat.message.*", "phone.notification.*"), s2.topics());
    }

    @Test
    void commandAndResult_payloads() throws Exception {
        // COMMAND
        CommandMessage cmd = new CommandMessage("chat.sendMessage", "idem-key-1",
                mapper.createObjectNode().put("conversationId", "conv-1").put("content", "hi"));
        DhcpFrame f = DhcpFrame.of(DhcpFrameType.COMMAND, "req-c", mapper.valueToTree(cmd));
        CommandMessage cmd2 = mapper.convertValue(f.payload(), CommandMessage.class);
        assertEquals("chat.sendMessage", cmd2.command());
        assertEquals("idem-key-1", cmd2.idempotencyKey());
        assertEquals("conv-1", cmd2.args().get("conversationId").asText());

        // COMMAND_RESULT ok
        CommandResultMessage ok = CommandResultMessage.ok("chat.sendMessage", "idem-key-1",
                mapper.createObjectNode().put("messageId", "m-1"));
        assertEquals(true, ok.ok());
        assertNull(ok.error());

        // COMMAND_RESULT fail
        CommandResultMessage fail = CommandResultMessage.fail("chat.sendMessage", "idem-key-1",
                DhcpError.of(DhcpErrorCode.SCOPE_DENIED, "缺少 scope"));
        assertEquals(false, fail.ok());
        assertEquals(DhcpErrorCode.SCOPE_DENIED, fail.error().code());
    }

    @Test
    void pingPong_roundTrip() throws Exception {
        PingMessage ping = PingMessage.now();
        PongMessage pong = PongMessage.echo(ping);
        assertEquals(ping.clientTimeMs(), pong.clientTimeMs());
        assertTrue(pong.serverTimeMs() > 0);
    }

    @Test
    void errorCarriesCodeAndMessage() {
        DhcpError err = DhcpError.of("AUTH_FAILED", "令牌无效");
        assertEquals("AUTH_FAILED", err.code());
        assertEquals("令牌无效", err.message());
        assertNull(err.details());
    }
}
