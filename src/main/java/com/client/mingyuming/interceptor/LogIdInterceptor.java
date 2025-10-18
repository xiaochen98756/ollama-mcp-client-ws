package com.client.mingyuming.interceptor;

import cn.hutool.core.util.IdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Logback LogID 拦截器：为每个HTTP请求生成唯一链路ID，放入MDC（Logback的ThreadContext）
 */
@Component
public class LogIdInterceptor implements HandlerInterceptor {
    // LogID在MDC中的key（需与logback.xml中的%X{logId}对应）
    private static final String LOG_ID_KEY = "logId";

    /**
     * 请求进入时：生成LogID并放入MDC
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 生成短UUID作为LogID（如：a1b2c3d4，长度8位，唯一且简洁）
        String logId = IdUtil.fastSimpleUUID();
        // 2. 放入MDC（Logback会从MDC中读取logId，对应%X{logId}）
        MDC.put(LOG_ID_KEY, logId);
        // 3. （可选）将LogID放入响应头，方便前端/客户端排查问题
        response.setHeader(LOG_ID_KEY, logId);
        return true;
    }

    /**
     * 请求结束后：清除MDC中的LogID（必须！避免线程池复用导致LogID混乱）
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        MDC.remove(LOG_ID_KEY);
    }
}