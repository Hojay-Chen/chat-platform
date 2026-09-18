package com.luxera.companion.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.server.ServerEndpointConfig;

/**
 * JSR-356 的容器**自己**实例化端点类(它不认识 Spring), 而
 * {@link ClientStreamEndpoint} 需要注入仓库/注册表。这个配置器把容器的那次实例化转交给
 * Spring 容器里那一个单例。
 *
 * <p>与 {@code simulator.server.SpringConfigurator} 是同一手法, 但**刻意分成两个类**:
 * 那一个的静态字段指的是 {@code SimulatorWebSocketController}, 两者共用一个类会让后注册的
 * 端点把前一个的实例覆盖掉 —— 而症状是"某一个端点上收到的连接其实是另一个端点的对象",
 * 极其难查。多一个十二行的类, 换掉一个这种可能。
 *
 * <p>静态字段是 JSR-356 逼出来的: 配置器由容器实例化, 它拿不到构造函数注入。这里能安全地用
 * 静态, 是因为 Spring 只会有<b>一个</b>这样的 bean(单例), 而它被创建时必然在容器启动阶段 ——
 * 那时任何一条 WS 连接都还没建立。
 */
@Component
public class ClientStreamConfigurator extends ServerEndpointConfig.Configurator {

    private static volatile ClientStreamEndpoint endpointInstance;

    @Autowired
    public void setEndpoint(ClientStreamEndpoint endpoint) {
        endpointInstance = endpoint;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (endpointClass == ClientStreamEndpoint.class && endpointInstance != null) {
            return endpointClass.cast(endpointInstance);
        }
        return super.getEndpointInstance(endpointClass);
    }
}
