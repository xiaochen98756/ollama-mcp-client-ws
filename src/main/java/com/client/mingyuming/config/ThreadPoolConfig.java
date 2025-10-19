package com.client.mingyuming.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {

    /**
     * 定义固定大小线程池（处理两个并发查询）
     */
    @Bean
    public ExecutorService executorService() {
        return Executors.newFixedThreadPool(2); // 刚好处理两个并发任务
    }
}