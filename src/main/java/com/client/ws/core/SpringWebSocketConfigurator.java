package com.client.ws.core;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @author WuFengSheng
 * @date 2025/4/20 08:00
 */
@Slf4j
@Component
public class SpringWebSocketConfigurator extends ServerEndpointConfig.Configurator {

    private static ApplicationContext applicationContext;

    public static void setApplicationContext(ApplicationContext context) {
        applicationContext = context;
    }


    @Override
    public <T> T getEndpointInstance(Class<T> clazz) {
        return applicationContext.getAutowireCapableBeanFactory().createBean(clazz);
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        final Map<String, Object> userProperties = sec.getUserProperties();
        log.info("userProperties: {}", userProperties);

        Map<String, List<String>> headers = request.getHeaders();
        log.info("headers: {}", headers);
        super.modifyHandshake(sec, request, response);
    }

}
