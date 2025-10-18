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
@ComponentScan(basePackages = {"com.client.mingyuming"})
public class AIApplication {

    public static void main(String[] args) {
        try {
            ApplicationContext context = SpringApplication.run(AIApplication.class, args);
            String[] beans= context.getBeanDefinitionNames();
            log.info("启动成功！{}",Arrays.stream(beans).toList());
        }catch (Exception e){
            log.error("启动失败",e);
        }
      }
}
