package com.client.mingyuming.service;

import com.client.mingyuming.dto.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 大模型调用服务（独立封装，供其他组件直接调用）
 */
@Slf4j
@Service
public class LlmService {
    // 从配置文件注入大模型参数
    @Value("${spring.ai.openai.base-url}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.chat.options.model}")
    private String modelId;

    private final RestTemplate restTemplate;

    // 仅依赖 RestTemplate，构造方法注入
    public LlmService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 调用大模型生成响应
     * @param chatRequest 包含 messages 列表的请求体（角色+内容）
     * @return 大模型生成的文本结果
     */
    public String generateResponse(ChatRequest chatRequest) {
        try {
            // 1. 构建大模型所需的请求体（包含 model 和 messages）
            Map<String, Object> llmRequest = new HashMap<>();
            llmRequest.put("model", modelId); // 必传：指定模型 ID

            // 2. 转换 ChatRequest 中的 messages 为大模型格式
            List<Map<String, String>> llmMessages = chatRequest.getMessages().stream()
                    .map(msg -> {
                        Map<String, String> messageMap = new HashMap<>();
                        messageMap.put("role", msg.getRole());
                        messageMap.put("content", msg.getContent());
                        return messageMap;
                    })
                    .collect(Collectors.toList());
            llmRequest.put("messages", llmMessages);

            // 3. 构建请求头（包含鉴权信息）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + openAiApiKey); // 大模型服务鉴权

            // 4. 发送 POST 请求到大模型服务
            String llmApiUrl = openAiBaseUrl + "/v1/chat/completions";
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(llmRequest, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(llmApiUrl, requestEntity, Map.class);

            // 5. 解析大模型响应（提取 content 字段）
            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("choices")) {
                throw new RuntimeException("大模型响应格式错误：" + responseBody);
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            Map<String, Object> firstChoice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
            // 解析大模型响应后，增加结果过滤
            String rawContent = (String) message.get("content");
            log.info("大模型原始输出：{}", rawContent);

            // 提取JSON的核心逻辑
            String jsonResult = extractJson(rawContent);

            log.info("大模型调用成功，生成内容：{}",jsonResult);
            return jsonResult;

        } catch (Exception e) {
            log.error("大模型调用失败", e);
            throw new RuntimeException("大模型调用异常：" + e.getMessage());
        }
    }
    /**
     * 从原始输出中提取JSON字符串（处理多余内容、格式错误等）
     */
    private String extractJson(String rawContent) {
        String content = rawContent.trim();
        // 移除可能的标签
        content = content.replaceAll("``", "");
        // 提取JSON部分（从第一个{到最后一个}）
        int startIdx = content.indexOf("{");
        int endIdx = content.lastIndexOf("}");
        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            return content.substring(startIdx, endIdx + 1).trim();
        }
        return content; // 非JSON情况（如错误提示）
    }
}