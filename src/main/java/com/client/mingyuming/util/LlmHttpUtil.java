package com.client.mingyuming.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.function.Function;

/**
 * 通用大模型 HTTP 调用工具类（简化版）
 * 封装公共请求逻辑，支持差异化 answer 处理
 */
@Slf4j
@Service
public class LlmHttpUtil {
    @Autowired
    RestTemplate restTemplate;
    private static final Gson gson = new Gson();



    /**
     * 通用大模型调用函数
     * @param baseUrl 基础地址（如 http://localhost:9222）
     * @param chatId 模型专属 chat-id
     * @param sessionId 会话 ID
     * @param authorization 鉴权 Token（Bearer 开头）
     * @param question 用户问题
     * @param answerProcessor 差异化 answer 处理器
     * @param <T> 处理结果类型
     * @return 最终处理结果
     */
    public <T> T call(
            String baseUrl,
            String chatId,
            String sessionId,
            String authorization,
            String question,
            Function<String, T> answerProcessor) {


        try {
            // 1. 构建请求 URL
            String requestUrl = baseUrl + "/api/v1/chats/" + chatId + "/completions";
            log.info("调用大模型：URL={}, 问题={}", requestUrl, question);

            // 2. 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", authorization);

            // 3. 构建请求体
            Map<String, Object> requestBody = Map.of(
                    "question", question,
                    "stream", false,
                    "session_id", sessionId
            );
            HttpEntity<String> requestEntity = new HttpEntity<>(
                    gson.toJson(requestBody), headers
            );

            // 4. 发送 HTTP 请求
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl, HttpMethod.POST, requestEntity, String.class
            );
            log.error("大模型应答：response={}", gson.toJson(response));
            // 5. 校验 HTTP 响应
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("大模型 HTTP 异常：状态码={}, 响应体={}", response.getStatusCode(), response.getBody());
                throw new RuntimeException("大模型请求失败，HTTP 状态码非 2xx");
            } else {
                response.getBody();
            }

            // 6. 解析顶层响应（code + data）
            Map<String, Object> responseMap = gson.fromJson(
                    response.getBody(), new TypeToken<Map<String, Object>>() {}.getType()
            );
            int code = ((Number) responseMap.getOrDefault("code", -1)).intValue();
            if (code != 0) {
                String msg = (String) responseMap.getOrDefault("message", "未知错误");
                log.error("大模型业务失败：code={}, 消息={}", code, msg);
                throw new RuntimeException("大模型返回错误：" + msg);
            }

            // 7. 提取并预处理 answer
            Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
            if (data == null) {
                throw new RuntimeException("大模型响应缺少 data 字段");
            }
            String answer = (String) data.get("answer");
            if (answer == null || answer.trim().isEmpty()) {
                throw new RuntimeException("大模型返回 answer 为空");
            }
            String trimmedAnswer = answer.trim();

            // 8. 差异化处理 answer 并返回
            return answerProcessor.apply(trimmedAnswer);

        } catch (Exception e) {
            log.error("大模型调用异常", e);
            throw new RuntimeException("调用大模型失败：" + e.getMessage());
        }
    }
}