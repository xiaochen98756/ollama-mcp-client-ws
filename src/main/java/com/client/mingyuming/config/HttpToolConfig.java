package com.client.mingyuming.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 工具调用配置：提供 RestTemplate 用于调用组委会 API
 */
@Configuration
public class HttpToolConfig {

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        // 可选：添加超时配置（避免工具 API 响应过慢导致比赛超时）
        restTemplate.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
            setConnectTimeout(3000); // 连接超时 3 秒
            setReadTimeout(5000);    // 读取超时 5 秒（匹配比赛单题超时要求）
        }});
        return restTemplate;
    }
}