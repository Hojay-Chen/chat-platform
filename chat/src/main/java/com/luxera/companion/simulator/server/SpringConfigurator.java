package com.luxera.companion.simulator.server;

import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SpringConfigurator extends ServerEndpointConfig.Configurator {

    private static SimulatorWebSocketController controllerInstance;

    @Autowired
    public void setSimulatorWebSocketController(SimulatorWebSocketController controller) {
        controllerInstance = controller;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (endpointClass == SimulatorWebSocketController.class && controllerInstance != null) {
            return endpointClass.cast(controllerInstance);
        }
        return super.getEndpointInstance(endpointClass);
    }
}