package com.client.mingyuming.config;

import com.client.mingyuming.interceptor.LogIdInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web配置：注册LogID拦截器
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final LogIdInterceptor logIdInterceptor;

    // 注入LogID拦截器（Spring自动扫描@Component注解的Bean）
    public WebMvcConfig(LogIdInterceptor logIdInterceptor) {
        this.logIdInterceptor = logIdInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 拦截所有请求，排除静态资源（如/favicon.ico，根据项目实际情况调整）
        registry.addInterceptor(logIdInterceptor)
                .addPathPatterns("/**")  // 所有请求都拦截
                .excludePathPatterns("/favicon.ico", "/static/**");  // 排除无需拦截的路径
    }
}