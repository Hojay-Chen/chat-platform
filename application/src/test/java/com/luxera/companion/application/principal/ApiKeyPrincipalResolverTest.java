package com.luxera.companion.application.principal;

import com.luxera.companion.access.ApiKeyGenerator;
import com.luxera.companion.access.ChatApiClient;
import com.luxera.companion.access.ChatApiClientRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 接入钥匙解析器 —— 三条性质, 每一条都对应一种"写错了也不报错"的失败。
 *
 * <ol>
 *   <li><b>解出来的 companionId 来自库, 不来自请求。</b> 这就是租户边界本身: 如果它来自
 *       任何调用方写的字段, 那么"每把钥匙只能碰它绑定的那个 Agent"就只是一句约定。</li>
 *   <li><b>管理钥匙判错就是 401, 绝不降级成客户端身份。</b> 一个既带着错的管理钥匙、
 *       又带着对的客户端钥匙的请求, 调用方想表达的是"我是管理员"; 悄悄按客户端身份
 *       把请求执行掉, 是把一次越权尝试变成一次成功。</li>
 *   <li><b>形状不对的钥匙连哈希都不算。</b> 不是安全考量(攻击者当然会带对前缀),
 *       而是别让扫描器的垃圾流量在库里查一遍。</li>
 * </ol>
 */
class ApiKeyPrincipalResolverTest {

    private static final String ADMIN_KEY = "admin-key-for-test";

    private final ChatApiClientRepository clients = mock(ChatApiClientRepository.class);

    private ApiKeyPrincipalResolver resolver(String adminKey) {
        return new ApiKeyPrincipalResolver(adminKey, clients);
    }

    // ── 客户端钥匙 ────────────────────────────────────────────────────────────

    @Test
    void aKnownKeyResolvesToTheAgentRecordedInTheDatabase() {
        ApiKeyGenerator.GeneratedKey key = ApiKeyGenerator.generate();
        ChatApiClient row = client("client-1", "agent-7");
        when(clients.findByApiKeyHashAndStatus(key.hash(), ChatApiClient.STATUS_ACTIVE))
                .thenReturn(Optional.of(row));

        ResolvedPrincipal me = resolver(ADMIN_KEY).resolve(
                PrincipalResolver.PrincipalRequest.ofApiKey(key.plaintext(), null, "corr-1"));

        assertEquals(PrincipalType.EXTERNAL_AGENT, me.type());
        assertEquals("agent-7", me.companionId(), "companionId 必须是库里那一列, 它是租户边界");
        assertEquals("client-1", me.principalId(), "principalId 是客户端 id —— 审计里要能答出是哪把钥匙");
        assertNull(me.userId(), "第三方程序不代表任何真人");
        assertEquals(ApiKeyPrincipalResolver.SOURCE_APIKEY, me.source());
    }

    @Test
    void anUnknownKeyIsRefused() {
        ApiKeyGenerator.GeneratedKey key = ApiKeyGenerator.generate();
        when(clients.findByApiKeyHashAndStatus(anyString(), anyString())).thenReturn(Optional.empty());

        PrincipalResolver.PrincipalException e = assertThrows(PrincipalResolver.PrincipalException.class,
                () -> resolver(ADMIN_KEY).resolve(
                        PrincipalResolver.PrincipalRequest.ofApiKey(key.plaintext(), null, "c")));
        assertEquals(ApiKeyPrincipalResolver.CODE_KEY_UNKNOWN, e.code());
    }

    /**
     * 已吊销与"从来没存在过"对外是同一件事。
     *
     * <p>区分开来对正常调用方毫无用处, 对一个拿着旧钥匙的人却是信息: "你这把曾经是对的"。
     */
    @Test
    void aRevokedKeyIsIndistinguishableFromAnUnknownOne() {
        ApiKeyGenerator.GeneratedKey key = ApiKeyGenerator.generate();
        // 查询条件里带着 status=ACTIVE —— REVOKED 的行根本不会被取出来
        when(clients.findByApiKeyHashAndStatus(key.hash(), ChatApiClient.STATUS_ACTIVE))
                .thenReturn(Optional.empty());

        PrincipalResolver.PrincipalException e = assertThrows(PrincipalResolver.PrincipalException.class,
                () -> resolver(ADMIN_KEY).resolve(
                        PrincipalResolver.PrincipalRequest.ofApiKey(key.plaintext(), null, "c")));
        assertEquals(ApiKeyPrincipalResolver.CODE_KEY_UNKNOWN, e.code());
    }

    @Test
    void aKeyWithoutThePrefixNeverReachesTheDatabase() {
        PrincipalResolver.PrincipalException e = assertThrows(PrincipalResolver.PrincipalException.class,
                () -> resolver(ADMIN_KEY).resolve(
                        PrincipalResolver.PrincipalRequest.ofApiKey("Bearer something-else", null, "c")));
        assertEquals(ApiKeyPrincipalResolver.CODE_KEY_UNKNOWN, e.code());
        verifyNoInteractions(clients);
    }

    // ── 管理钥匙 ──────────────────────────────────────────────────────────────

    @Test
    void theAdminKeyResolvesToSystemAndCarriesNoAgent() {
        ResolvedPrincipal me = resolver(ADMIN_KEY).resolve(
                PrincipalResolver.PrincipalRequest.ofApiKey(null, ADMIN_KEY, "c"));

        assertEquals(PrincipalType.SYSTEM, me.type());
        assertEquals(ApiKeyPrincipalResolver.ADMIN_PRINCIPAL_ID, me.principalId());
        assertNull(me.companionId(), "管理员不代表任何 Agent —— 管理面据此无法'顺手'读某个 Agent 的会话");
        verifyNoInteractions(clients);
    }

    /** 管理钥匙没配 = 管理面是死端点, 而不是"无鉴权可进"。 */
    @Test
    void anUnconfiguredAdminKeyMeansDisabledNotOpen() {
        PrincipalResolver.PrincipalException e = assertThrows(PrincipalResolver.PrincipalException.class,
                () -> resolver("").resolve(
                        PrincipalResolver.PrincipalRequest.ofApiKey(null, "anything", "c")));
        assertEquals(ApiKeyPrincipalResolver.CODE_ADMIN_DISABLED, e.code());
    }

    @Test
    void aWrongAdminKeyIsRefused() {
        PrincipalResolver.PrincipalException e = assertThrows(PrincipalResolver.PrincipalException.class,
                () -> resolver(ADMIN_KEY).resolve(
                        PrincipalResolver.PrincipalRequest.ofApiKey(null, "not-the-admin-key", "c")));
        assertEquals(ApiKeyPrincipalResolver.CODE_ADMIN_UNAUTHORIZED, e.code());
    }

    /**
     * 两个头都在、且管理钥匙是错的 —— 拒掉, 而不是"那按客户端钥匙办吧"。
     *
     * <p>这是这个解析器里唯一一处"两个身份都给了"的情况。按客户端办看起来更宽容,
     * 实际效果是把一次<b>够到管理面的尝试</b>变成一次成功的普通调用, 而且调用方拿到的是
     * 一个成功的答复 —— 它会以为自己的管理钥匙是对的, 然后在下一个端点上撞墙。
     */
    @Test
    void aWrongAdminKeyDoesNotSilentlyDowngradeToTheClientKey() {
        ApiKeyGenerator.GeneratedKey key = ApiKeyGenerator.generate();
        when(clients.findByApiKeyHashAndStatus(key.hash(), ChatApiClient.STATUS_ACTIVE))
                .thenReturn(Optional.of(client("client-1", "agent-7")));

        PrincipalResolver.PrincipalException e = assertThrows(PrincipalResolver.PrincipalException.class,
                () -> resolver(ADMIN_KEY).resolve(
                        PrincipalResolver.PrincipalRequest.ofApiKey(key.plaintext(), "wrong", "c")));
        assertEquals(ApiKeyPrincipalResolver.CODE_ADMIN_UNAUTHORIZED, e.code());
        verify(clients, never()).findByApiKeyHashAndStatus(anyString(), anyString());
    }

    // ── supports: 这是不是我的活 ──────────────────────────────────────────────

    @Test
    void supportsOnlyRequestsThatCarryOneOfItsTwoHeaders() {
        ApiKeyPrincipalResolver r = resolver(ADMIN_KEY);

        assertTrue(r.supports(PrincipalResolver.PrincipalRequest.ofApiKey("cak_x", null, "c")));
        assertTrue(r.supports(PrincipalResolver.PrincipalRequest.ofApiKey(null, "admin", "c")));
        assertFalse(r.supports(PrincipalResolver.PrincipalRequest.ofApiKey(null, null, "c")));
        assertFalse(r.supports(PrincipalResolver.PrincipalRequest.ofApiKey("  ", "", "c")));
        assertFalse(r.supports(null));
        // 关键: LAP 的普通请求(只有 Authorization)不在本解析器的辖区里 ——
        // 一条 cak_ 钥匙因此没有任何办法在别的端点上冒充身份。
        assertFalse(r.supports(PrincipalResolver.PrincipalRequest.ofHeader("Bearer jwt", "c")));
        assertFalse(r.supports(PrincipalResolver.PrincipalRequest.ofMcp("AGENT:1", "svc", "c")));
    }

    private static ChatApiClient client(String clientId, String agentId) {
        ChatApiClient c = new ChatApiClient();
        c.setClientId(clientId);
        c.setAgentId(agentId);
        c.setStatus(ChatApiClient.STATUS_ACTIVE);
        return c;
    }
}
