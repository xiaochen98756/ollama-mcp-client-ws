package com.client.mingyuming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class OllamaMcpClientWsApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(OllamaMcpClientWsApplication.class, args);
    }

}
