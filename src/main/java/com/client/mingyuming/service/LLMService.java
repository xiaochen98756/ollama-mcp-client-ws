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
public class LLMService {
    // 从配置文件注入大模型参数
    @Value("${spring.ai.openai.base-url}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.chat.options.model}")
    private String modelId;

    private final RestTemplate restTemplate;

    // 仅依赖 RestTemplate，构造方法注入
    public LLMService(RestTemplate restTemplate) {
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
            String jsonResult = preprocessJson(rawContent);

            log.info("大模型调用成功，生成内容：{}",jsonResult);
            return jsonResult;

        } catch (Exception e) {
            log.error("大模型调用失败", e);
            throw new RuntimeException("大模型调用异常：" + e.getMessage());
        }
    }

    /**
     * JSON 预处理：从末尾提取最后一个完整 JSON 串，清理格式后返回（适配大模型先描述后输出 JSON 的场景）
     */
    private String preprocessJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return json;
        }

        // 1. 关键优化：从末尾反向查找最后一个完整的 JSON 片段（{} 包裹）
        int lastJsonEnd = json.lastIndexOf("}"); // 先找最后一个 "}"（JSON 结束符）
        int lastJsonStart = -1;
        if (lastJsonEnd != -1) {
            // 从最后一个 "}" 往前找第一个 "{"（匹配对应的 JSON 开始符）
            lastJsonStart = json.lastIndexOf("{", lastJsonEnd);
        }

        // 验证找到的片段是否是完整 JSON（必须同时找到 { 和 }，且 { 在 } 前面）
        if (lastJsonStart != -1 && lastJsonEnd != -1 && lastJsonStart < lastJsonEnd) {
            // 提取最后一个 {} 包裹的内容（这是大模型最终输出的目标 JSON）
            json = json.substring(lastJsonStart, lastJsonEnd + 1);
            log.debug("从末尾提取到的纯 JSON 片段：{}", json);
        } else {
            // 未找到完整 JSON，返回原内容并告警（便于定位问题）
            log.warn("未找到完整 JSON 结构（输入：{}），可能不是有效 JSON", json.length() > 100 ? json.substring(0, 100) + "..." : json);
            return json;
        }

        // 2. 保留原有格式清理逻辑（处理 JSON 内部的不规范格式）
        String noCommentJson = json.replaceAll("//.*|/\\*[\\s\\S]*?\\*/", ""); // 去除单行/多行注释
        String noSingleQuoteJson = noCommentJson.replaceAll("'", "\""); // 单引号转双引号（JSON 要求双引号）
        String trimJson = noSingleQuoteJson.trim(); // 去除 JSON 内部首尾多余空格

        // 3. 额外处理：去除 JSON 内部可能的换行符（避免 Gson 解析换行符报错）
        String noLineBreakJson = trimJson.replaceAll("\\r?\\n", " ");

        return noLineBreakJson;
    }
}