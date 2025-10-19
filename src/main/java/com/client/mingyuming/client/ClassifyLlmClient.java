package com.client.mingyuming.client;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;

/**
 * 分类大模型调用客户端（用于判断请求类型：知识问答/工具调用/数据查询）
 */
@Slf4j
@Component
public class ClassifyLlmClient {
    private final RestTemplate restTemplate;
    private final Gson gson = new Gson();

    // 从配置文件注入分类大模型参数
    @Value("${classify-llm.base-url}")
    private String baseUrl;
    @Value("${classify-llm.chat-id}")
    private String chatId;
    @Value("${classify-llm.session-id}")
    private String sessionId;
    @Value("${classify-llm.authorization}")
    private String authorization;



    // 构造方法注入RestTemplate（复用项目已有实例，或单独创建）
    public ClassifyLlmClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 调用分类大模型，返回请求类型
     * @param question 原始问题（来自ExamRequestDTO的question字段）
     * @return 分类结果（固定返回：knowledge_qa/tool_call/data_query）
     */
    /**
     * 调用分类大模型，返回请求类型
     * @param question 原始问题（来自ExamRequestDTO的question字段）
     * @return 分类结果（固定返回：knowledge_qa/tool_call/data_query）
     */
    public String getRequestType(String question) {
        String requestUrl = baseUrl + "/api/v1/chats/" + chatId + "/completions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", authorization);

        // 构建请求体（原有逻辑不变）
        ClassifyRequest requestBody = new ClassifyRequest();
        requestBody.setQuestion(question);
        requestBody.setStream(false);
        requestBody.setSession_id(sessionId);
        HttpEntity<String> requestEntity = new HttpEntity<>(gson.toJson(requestBody), headers);
        log.info("调用分类大模型：URL={}，请求体={}", requestUrl, gson.toJson(requestBody));

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl, HttpMethod.POST, requestEntity, String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                // 1. 解析顶层响应体
                ClassifyLlmResponse llmResponse = gson.fromJson(response.getBody(), ClassifyLlmResponse.class);

                // 2. 校验状态码：仅 code=0 视为成功
                if (llmResponse.getCode() != 0) {
                    log.error("分类大模型业务失败：code={}，message={}", llmResponse.getCode(), llmResponse.getMessage());
                    return "knowledge_qa"; // 降级为知识问答
                }

                // 3. 校验会话ID一致性（避免调用错会话）
                ClassifyData data = llmResponse.getData();
                if (data == null || !sessionId.equals(data.getSession_id())) {
                    log.error("分类大模型会话ID不一致：配置={}，返回={}", sessionId, data != null ? data.getSession_id() : "null");
                    return "knowledge_qa";
                }

                // 4. 预处理 answer：trim 去前后空格（关键：避免空格导致JSON解析失败）
                String answer = data.getAnswer();
                if (answer == null) {
                    log.error("分类大模型返回 answer 为空");
                    return "knowledge_qa";
                }
                String trimmedAnswer = answer.trim(); // 去前后空格、换行符
                if (trimmedAnswer.isEmpty()) {
                    log.error("分类大模型返回 answer trim 后为空");
                    return "knowledge_qa";
                }

                // 5. 从 trimmedAnswer（JSON串）中提取 requestType（核心修改）
                String requestType = extractTypeFromJsonAnswer(trimmedAnswer);
                log.info("分类大模型解析结果：question={}，trimmedAnswer={}，提取requestType={}",
                        question, trimmedAnswer, requestType);

                // 6. 校验提取的类型是否合法
                return isLegalType(requestType) ? requestType : "knowledge_qa";
            } else {
                log.error("分类大模型HTTP响应异常：状态码={}，响应体={}", response.getStatusCode(), response.getBody());
                return "knowledge_qa";
            }

        } catch (Exception e) {
            log.error("调用分类大模型失败：question={}", question, e);
            return "knowledge_qa";
        }
    }

    /**
     * 新增：从 JSON 格式的 answer 中提取 requestType（适配大模型返回格式）
     * 示例：trimmedAnswer={"requestType": "tool_call"} → 提取 "tool_call"
     */
    private String extractTypeFromJsonAnswer(String trimmedAnswer) {
        try {
            // 将 trimmedAnswer 解析为 JSON 对象，提取 requestType 字段
            // 用 Gson 解析为 Map（灵活适配，无需额外定义类）
            Map<String, String> answerJson = gson.fromJson(trimmedAnswer, new TypeToken<Map<String, String>>() {}.getType());
            if (answerJson == null) {
                log.error("JSON 解析结果为空：trimmedAnswer={}", trimmedAnswer);
                return "knowledge_qa";
            }

            // 提取 requestType 并去空格（避免大模型返回 " knowledge_qa " 这类带空格的情况）
            String requestType = answerJson.getOrDefault("requestType", "").trim();
            if (requestType.isEmpty()) {
                log.error("JSON 中未找到 requestType 字段或字段值为空：trimmedAnswer={}", trimmedAnswer);
                return "knowledge_qa";
            }

            return requestType;

        } catch (JsonSyntaxException e) {
            // 若 JSON 解析失败（如格式错误），日志告警并降级
            log.warn("分类大模型 answer 不是合法 JSON：trimmedAnswer={}，错误信息={}", trimmedAnswer, e.getMessage());
            return "knowledge_qa";
        }
    }

    /**
     * 校验提取的类型是否合法（仅允许3种，原有逻辑不变）
     */
    private boolean isLegalType(String type) {
        return "knowledge_qa".equals(type) || "tool_call".equals(type) || "data_query".equals(type);
    }

    // 内部类：分类大模型请求体格式
    @Data
    private static class ClassifyRequest {
        private String question;
        private boolean stream;
        private String session_id;
    }

    // 内部类：顶层响应体类（匹配实际返回格式：{code:0, data:{...}}）
    @Data
    private static class ClassifyLlmResponse {
        private Integer code; // 状态码：0=成功，非0=失败
        private ClassifyData data; // 核心数据体
        private String message; // 可选：错误提示（code非0时返回）
    }

    // data 层级数据类（匹配实际 data 结构）
    @Data
    private static class ClassifyData {
        private String answer; // 分类结果/答案（关键：从中提取请求类型）
        private String audio_binary; // 无用字段：忽略
        private String id; // 响应ID：日志记录用
        private String prompt; // 原始提示：日志记录用
        private ClassifyReference reference; // 参考文档：忽略
        private String session_id; // 会话ID：校验一致性用
    }

    // reference 层级（无用，仅为解析JSON用）
    @Data
    private static class ClassifyReference {
        private List<Object> chunks; // 空列表：忽略
        private List<Object> doc_aggs; // 空列表：忽略
        private Integer total; // 总数：忽略
    }
}