package com.client.mingyuming;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;

import java.util.Arrays;

@Slf4j
@SpringBootApplication
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.client.mingyuming"}) // 确保包含所有需要扫描的包
public class AIApplication {

    public static void main(String[] args) {
        try {
            ApplicationContext context = SpringApplication.run(AIApplication.class, args);
            String[] beans= context.getBeanDefinitionNames();
            Arrays.stream(beans)
                    .filter(name -> name.contains("chatService") || name.contains("chatClient"))
                    .forEach(name -> log.info("已加载的 Bean：" + name));
            log.info("启动成功！{}",beans);
        }catch (Exception e){
            log.error("启动失败",e);
        }
      }
}
