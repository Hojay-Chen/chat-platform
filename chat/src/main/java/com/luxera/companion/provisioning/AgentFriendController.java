package com.luxera.companion.provisioning;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.provision.PersonaJson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一键创建 Agent 好友 —— 需求 ④ 的入口。
 *
 * <h2>它为什么是顶层路径 {@code /api/agent-friends}, 而不是塞进 {@code /api/companions/**}</h2>
 *
 * <p><b>因为那个前缀不属于本进程。</b>仓 1 的 {@code CompanionDomainProxyController} 把
 * {@code /api/companions} 与 {@code /api/companions/**} 整段**兜底转发**给 8091 —— 它是
 * 一个 `/**` 的通配 mapping, 凡是落在那个前缀下的新端点都会被它截走, 表现为一个
 * 语焉不详的 404/502, 而控制器就好好地写在这里。这个坑在 P3 加 {@code /api/persons/**}
 * 时已经踩过一次(见那个类的类注释), 所以这次直接把端点放在外面。
 *
 * <p>它也不是 {@code /api/conversations} 那种按资源划分的路径: 一键创建**同时动三样东西**
 * (一个聊天账号、一个 agent、一个会话), 它是一次编排而不是某个资源的 CRUD。
 * 路径名说的是这件事本身。
 *
 * <h2>鉴权: 普通 JWT —— 没有任何例外</h2>
 *
 * <p>落在 {@code SecurityConfig} 的 {@code anyRequest().authenticated()} 后面, 不需要在那里
 * 加任何一行。这很重要: 这条链是**以登录用户的名义**建好友的(建出来的 agent 归他所有),
 * 而那个身份只可能来自 JWT。
 *
 * <h2>{@code ownerUserId} 只从 JWT 取, 不从请求体取 —— 这是一个安全边界</h2>
 *
 * <p>下游的 {@code AgentRegistrationRequest} 里**有** {@code ownerUserId} 这个字段, 因为
 * 仓 2 那个 openAPI 端点也要服务第三方程序(它们替自己的用户建 agent 是合法的)。但本端点
 * 的调用方永远是**浏览器里的一个真人**, 所以它的值只能是
 * {@link CurrentUser#requireUserId()}。
 *
 * <p>让请求体带这个字段的后果是具体的: 任何登录用户都能建出一个**归别人所有**的 agent,
 * 而那个 agent 会出现在别人的通讯录里 —— 一个他从来没同意过的"好友", 而且他删不掉它
 * (代建出来的 agent 对代建方只读, 归属方才是所有者; 但这里归属方压根不知情)。
 * 请求体里**根本没有这个字段**, 所以这不是"记得校验"的问题, 而是没有地方可以填错。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-friends")
public class AgentFriendController {

    private final AgentFriendProvisioningService provisioning;
    private final CurrentUser currentUser;

    public AgentFriendController(AgentFriendProvisioningService provisioning, CurrentUser currentUser) {
        this.provisioning = provisioning;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateBody body) {
        String userId = currentUser.requireUserId();
        if ((body.description() == null || body.description().isBlank()) && body.persona() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "description 与 persona 必须给一个"));
        }
        if (body.requestId() == null || body.requestId().isBlank()) {
            // requestId 是幂等锚点, 不给它的话重试就会铸出第二个账号 —— 而"重试"在一键创建里
            // 是常态(用户双击、前端超时重发)。所以这里不是"选填", 是要么给要么别调。
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "requestId 不能为空",
                    "hint", "它是幂等键: 同一次意图请复用同一个值, 重试才不会铸出第二个账号"));
        }

        AgentFriendProvisioningService.ProvisionedAgentFriend created = provisioning.create(
                userId, body.requestId().trim(),
                body.description(), body.persona(), body.relationshipType());

        // 成功 → 201。**重试命中已有 agent 时也走这里**, 而不是 200: 从调用方的角度这次
        // 意图确实达成了, 而他拿到的 body 与第一次完全一样(两步各自幂等)。要区分"这次是不是
        // 真的新建了"需要另一个信号, 而那个信号对界面没有任何用 —— 它只会在用户重试成功后
        // 显示一个迷惑的"已存在"。
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("agentId", created.agentId());
        resp.put("chatAccountId", created.chatAccountId());
        resp.put("handle", created.handle());
        resp.put("name", created.name());
        resp.put("pairingCode", created.pairingCode());
        resp.put("pairingCodeExpiresAt", created.pairingCodeExpiresAt() == null
                ? null : created.pairingCodeExpiresAt().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 请求体。
     *
     * <p>四个字段里<b>没有</b> {@code ownerUserId} 也<b>没有</b> {@code chatAccountId}:
     * 前者由 JWT 决定(见类注释), 后者是第一步的产物 —— 调用方在调这个端点之前根本没有它。
     * 两个都不是"参数", 所以都不该出现在这里。
     *
     * <p>{@code description} 与 {@code persona} 二选一, 与仓 2 openAPI 的约定一致。
     */
    public record CreateBody(String requestId, String description, PersonaJson persona,
                             String relationshipType) {}
}
