package com.client.ws.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * @author WuFengSheng
 * @date 2025/4/20 07:03
 */
@Slf4j
@Configuration
@EnableWebSocket
public class WebSocketConfig {

    /**
     * 自动注册使用 @ServerEndpoint 注解的 WebSocket 端点
     */
    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

}
