package com.client.mingyuming.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.function.Function;

/**
 * 通用大模型 HTTP 调用工具类
 */
@Slf4j
@Service
public class LlmHttpUtil {
    @Autowired
    RestTemplate restTemplate;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    /**
     * 通用大模型调用函数（核心复用入口）
     */
    public <T> T call(
            String modelName,
            String baseUrl,
            String chatId,
            String sessionId,
            String authorization,
            String question,
            Function<String, T> answerProcessor) {

        try {
            // 参数校验
            validateParams(modelName, baseUrl, chatId, authorization, question);

            // 构建请求URL
            String requestUrl = baseUrl + "/api/v1/chats/" + chatId + "/completions";
            log.info("【{}】调用URL：{}，会话ID：{}", modelName, requestUrl, sessionId);

            // 构建请求实体
            HttpEntity<String> requestEntity = buildRequestEntity(authorization, sessionId, question);

            // 执行请求并解析响应
            String trimmedAnswer = executeRequestAndParseResponse(modelName, requestUrl, requestEntity);

            // 差异化处理并返回
            return answerProcessor.apply(trimmedAnswer);

        } catch (Exception e) {
            log.error("【{}】调用失败：{}", modelName, e.getMessage(), e);
            throw new RuntimeException("【" + modelName + "】调用失败：" + e.getMessage());
        }
    }

    /**
     * 参数校验（复用：避免重复判断）
     */
    private void validateParams(String modelName, String baseUrl, String chatId, String authorization, String question) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("【" + modelName + "】baseUrl 不能为空");
        }
        if (chatId == null || chatId.trim().isEmpty()) {
            throw new IllegalArgumentException("【" + modelName + "】chatId 不能为空");
        }
        if (authorization == null || authorization.trim().isEmpty()) {
            throw new IllegalArgumentException("【" + modelName + "】authorization 不能为空");
        }
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("【" + modelName + "】用户问题不能为空");
        }
    }

    /**
     * 构建请求实体（复用：统一格式）
     */
    private HttpEntity<String> buildRequestEntity(String authorization, String sessionId, String question) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", authorization);

        Map<String, Object> requestBody = Map.of(
                "question", question,
                "stream", false,
                "session_id", sessionId != null ? sessionId : ""
        );
        return new HttpEntity<>(gson.toJson(requestBody), headers);
    }

    /**
     * 执行请求并解析响应（复用：统一解析逻辑）
     */
    private String executeRequestAndParseResponse(String modelName, String requestUrl, HttpEntity<String> requestEntity) {
        ResponseEntity<String> response = restTemplate.exchange(
                requestUrl, HttpMethod.POST, requestEntity, String.class
        );
        log.debug("【{}】原始响应：{}", modelName, response.getBody());

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("HTTP状态异常：" + response.getStatusCode());
        }

        Map<String, Object> responseMap = gson.fromJson(response.getBody(), new TypeToken<Map<String, Object>>() {}.getType());
        int code = ((Number) responseMap.getOrDefault("code", -1)).intValue();
        if (code != 0) {
            String msg = (String) responseMap.getOrDefault("message", "未知错误");
            throw new RuntimeException("业务码异常：" + code + "，消息：" + msg);
        }

        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        if (data == null) {
            throw new RuntimeException("响应缺少data字段");
        }

        String answer = (String) data.get("answer");
        if (answer == null || answer.trim().isEmpty()) {
            throw new RuntimeException("answer为空");
        }

        return answer.trim();
    }
}