package com.luxera.companion.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * G8 —— 伴侣域请求到底被**哪个处理器**接走。
 *
 * <p>这是 G5 判错归属的那一处, 也是本设计的核心主张: 分流不写表, 由 Spring 的
 * HandlerMapping 算 —— 8081 自己实现的端点有更精确的映射, 赢过
 * {@code CompanionDomainProxyController} 的 {@code /api/companions/**} 兜底,
 * 其余落到兜底上被转给 8091。
 *
 * <p>断言的是 <b>handler 类型</b>而不是响应码: 响应码要起 8091 才说得清
 * (转发的请求在 8091 缺席时是 502), 而"谁接走"是纯映射问题, 不依赖下游。这样
 * 这个测试既不需要数据库里的伴侣, 也不需要在 CI 里拉起第二个服务 —— 它钉住的是
 * <b>路由规则本身</b>, 而那正是会悄悄出错的部分。
 *
 * <p>为什么 G5 的 36 条 {@code route()} 断言拦不住这个错: 它断言的是自己那张
 * "路径段 → 服务"的表, 表错了断言就跟着错 —— 而表把 {@code conversations} 整段
 * 划给了 8081, 于是 {@code conversations/first} 和 {@code conversations/{cid}/chat}
 * (都是 8091 的端点: {@code /first} 开/复用会话返回 ConversationView, {@code /chat} 是 SSE)
 * 被送去一个没有它们的服务。本测试的第一批用例就是这两条,
 * 它们会直接失败在 G5 的实现上。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.luxera.chatserver.ChatPlatformApplication.class)
class CompanionDomainRoutingTest {

    @Autowired
    private WebApplicationContext wac;

    private String handlerFor(String method, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRequestURI(path);
        // Spring 5.3 起 HandlerMapping 不再自己解析路径, 而是读请求属性里缓存好的
        // RequestPath。真实请求由 DispatcherServlet 前端的过滤器填; 手工构造的
        // MockHttpServletRequest 没经过那一步, 不补这一句会抛
        // "Expected parsed RequestPath in request attribute"。
        ServletRequestPathUtils.parseAndCache(req);
        HandlerExecutionChain chain = wac.getBean(RequestMappingHandlerMapping.class).getHandler(req);
        assertNotNull(chain, "没有任何处理器接住 " + method + " " + path);
        Object handler = chain.getHandler();
        assertNotNull(handler, method + " " + path + " 的处理器为空");
        return ((HandlerMethod) handler).getBeanType().getSimpleName();
    }

    /**
     * 8091 的端点 —— 必须落到转发兜底。前两条是 G5 判错、导致流式聊天 404 的回归点。
     */
    @Test
    void companion_domain_endpoints_are_proxied_to_agent_platform() throws Exception {
        String[][] proxied = {
                // ★ G5 回归点: 路径段是 conversations, 但端点属于 8091
                // (/first 返回 ConversationView 的 JSON; /chat 才是 SSE 流)
                {"POST", "/api/companions/c1/conversations/first"},
                {"POST", "/api/companions/c1/conversations/conv-1/chat"},
                // 伴侣 CRUD 本身(G1 已迁去 8091, 8081 无此映射)
                {"GET", "/api/companions"},
                {"POST", "/api/companions"},
                {"GET", "/api/companions/c1"},
                {"DELETE", "/api/companions/c1"},
                {"GET", "/api/companions/c1/memories"},
                {"POST", "/api/companions/c1/memories/search"},
                {"GET", "/api/companions/c1/relationship"},
                {"GET", "/api/companions/c1/relationship/narrative"},
                {"GET", "/api/companions/c1/life"},
                {"GET", "/api/companions/c1/self"},
                {"GET", "/api/companions/c1/state"},
                {"GET", "/api/companions/c1/reminders"},
                {"GET", "/api/companions/c1/notifications"},
                {"GET", "/api/companions/c1/user-model/facts"},
                {"GET", "/api/companions/c1/reflections"},
                {"GET", "/api/companions/c1/persona"},
                {"POST", "/api/companions/compile"},
                {"POST", "/api/companions/preview"},
                // 真人改自己的账号ID —— persons 表只有 8091 有映射, 所以走同一条转发路。
                // 断言的是 handler 类型: 万一将来有人在 8081 也实现了一个 /api/persons/...,
                // 它会因为映射更精确而赢过兜底, 这条用例就会红 —— 那正是需要被看见的时刻,
                // 因为那意味着授权判断被搬到了一个没有 persons 表的进程里。
                {"GET", "/api/persons/me/handle"},
                {"PUT", "/api/persons/me/handle"},
        };
        for (String[] c : proxied) {
            assertEquals(CompanionDomainProxyController.class.getSimpleName(), handlerFor(c[0], c[1]),
                    c[0] + " " + c[1] + " 应转发给 8091");
        }
    }

    /**
     * 8081 本地实现的伴侣域端点 —— 必须留在本地。它们与上面同处
     * {@code /api/companions} 前缀之下, 靠映射更精确取胜。
     */
    @Test
    void chat_owned_endpoints_stay_local() throws Exception {
        String[][] local = {
                {"GET", "/api/companions/c1/conversations"},
                {"POST", "/api/companions/c1/conversations"},
                {"GET", "/api/companions/c1/conversations/conv-1/messages"},
                {"GET", "/api/companions/c1/conversations/conv-1/participants"},
                {"GET", "/api/companions/c1/conversations/conv-1/applications"},
                {"GET", "/api/companions/c1/events"},
                {"GET", "/api/companions/c1/threads"},
        };
        for (String[] c : local) {
            String handler = handlerFor(c[0], c[1]);
            // 断言"不是兜底"而不是"是某个具体类": 会话域将来增删控制器不该弄红这个测试,
            // 但**任何**一条会话域请求掉进转发兜底都是回归 —— 那意味着它会被送去一个
            // 没有该端点的服务, 前端拿 404 或 502。
            assertEquals(false, CompanionDomainProxyController.class.getSimpleName().equals(handler),
                    c[0] + " " + c[1] + " 属 8081, 却落到了转发兜底上 (handler=" + handler + ")");
        }
    }

    /**
     * persons 域**整个前缀只有一个归属: 转发兜底** —— 裸路径也算在内。
     *
     * <p>这条用例的前身断言的是"裸 {@code /api/persons} 不该有任何处理器", 依据是
     * "只加了 {@code /api/persons/**}, 而 {@code /**} 匹配不到无子路径的形式"。
     * <b>那个依据是错的</b>: Boot 2.7 默认的 PathPattern 里 {@code **} 可以匹配零个路径段,
     * 所以一条 {@code /api/persons/**} 就把裸路径也吃进来了(实测: 该用例红了, 而映射本身
     * 与当初的计划一字不差)。同样的事也发生在 {@code /api/companions} 上 ——
     * 它被与 {@code /api/companions/**} 并列写出, 其实是冗余的。
     *
     * <p>于是断言改成真正该守的那条: <b>8081 绝不认领 persons 域的任何一段</b>。
     * 这与 companions 域的形状完全一致, 也正是"授权判断必须在持有 persons 表的一侧做"
     * 这句话在路由层的表现 —— 哪天有人在 8081 写了个 {@code /api/persons/...} 控制器,
     * 它会因为映射更精确而赢过兜底, 这条用例就红。
     */
    @Test
    void the_persons_prefix_has_exactly_one_owner() throws Exception {
        for (String path : new String[]{
                "/api/persons",              // 裸路径: 被 /** 一并覆盖, 见上面注释
                "/api/persons/me/handle",
                "/api/persons/whatever",     // 8091 也不存在的路径, 照样归它 —— 兜底就是兜底
        }) {
            assertEquals(CompanionDomainProxyController.class.getSimpleName(), handlerFor("GET", path),
                    path + " 应归 8091 —— persons 表不在本进程里");
        }
    }

    /** 非伴侣域不受影响 —— 兜底只吃 /api/companions 与 /api/persons。 */
    @Test
    void other_api_prefixes_are_untouched() throws Exception {
        // 注意别把 /api/v1/memories 写进来 —— 它不存在(旧 route 测试的夹具字符串冒充实端点),
        // 加了会让本测试因为"没有处理器"而失败, 而那跟兜底有没有越界毫无关系。
        for (String[] c : new String[][]{
                {"POST", "/api/auth/login"},
                {"GET", "/api/v1/applications"},
                {"GET", "/api/v1/capabilities"},
                {"GET", "/api/v1/resources"},
        }) {
            String handler = handlerFor(c[0], c[1]);
            assertEquals(false, CompanionDomainProxyController.class.getSimpleName().equals(handler),
                    c[0] + " " + c[1] + " 不该被伴侣域兜底截走");
        }
    }
}
