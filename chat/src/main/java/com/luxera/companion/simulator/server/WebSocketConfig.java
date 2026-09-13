package com.luxera.companion.simulator.server;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

import javax.servlet.ServletContext;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;

/**
 * V10 §63: 导出 JSR-356 @ServerEndpoint(/ws/simulator)。
 *
 * 关键时序: Tomcat 的 WsSci 在 ServletContext 初始化时已经把 ServerContainer
 * 写入 servletContext 属性。ServerEndpointExporter 需要这个属性来注册端点。
 * Spring 在 servlet 容器完全启动之前就实例化单例 bean, 所以:
 * 1. 用 @ConditionalOnWebApplication 确保只在 SERVLET 真实环境加载
 * 2. 用 @ConditionalOnClass 避免 MOCK 测试加载(那里 servletContext 不可用)
 * 3. 直接从 ServletContext 读 ServerContainer, 不依赖 Spring 自动注入
 */
@Slf4j
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(ServerEndpointExporter.class)
public class WebSocketConfig {

    /**
     * 在真实 SERVLET 容器中注册 WS 端点导出器。
     * ServerContainer 在 servletContext 初始化阶段被 WsSci 注入; 我们从那里读取。
     * (Spring 不会自动注入 ServerContainer 到 @Configuration 类中。)
     */
    @Bean
    public ServerEndpointExporter serverEndpointExporter(ApplicationContext context) {
        if (!(context instanceof WebApplicationContext wac)) {
            log.info("[WebSocketConfig] 非 WebApplicationContext, 跳过 @ServerEndpoint 导出");
            return null;
        }
        if (!(wac.getServletContext() != null)) {
            log.info("[WebSocketConfig] ServletContext 不可用, 跳过 @ServerEndpoint 导出");
            return null;
        }
        ServletContext sc = wac.getServletContext();
        Object attr = sc.getAttribute("javax.websocket.server.ServerContainer");
        if (!(attr instanceof ServerContainer container)) {
            log.info("[WebSocketConfig] ServerContainer 不在 servlet context, 跳过");
            return null;
        }
        ServerEndpointExporter exporter = new ServerEndpointExporter();
        exporter.setServerContainer(container);
        exporter.setAnnotatedEndpointClasses(SimulatorWebSocketController.class);
        log.info("[WebSocketConfig] @ServerEndpoint 导出配置完成: /ws/simulator");
        return exporter;
    }
}