package com.client.mingyuming.controller;

import com.client.mingyuming.dto.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 仅支持 POST 调用的聊天控制器，适配 ChatRequest 格式
 */
@Slf4j
@RestController
@RequestMapping("/openai/chat")
public class OpenAiChatClientController {
    @Value("${spring.ai.openai.base-url}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.chat.options.model}")
    private String modelId;

    @Autowired
    private RestTemplate restTemplate; // 需要注入 RestTemplate
    private final ChatClient openAiChatClient;
    private static final String DEFAULT_PROMPT = "你是一个聊天助手，请根据用户提问回答！";

    // 初始化 ChatClient（保留会话记忆和日志配置）
    public OpenAiChatClientController(ChatClient.Builder chatClientBuilder) {
        this.openAiChatClient = chatClientBuilder
                .defaultSystem(DEFAULT_PROMPT)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(new InMemoryChatMemory()) // 会话记忆支持
                )
                .defaultAdvisors(
                        new SimpleLoggerAdvisor() // 日志打印
                )
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                .topP(0.7)
                                .build()
                )
                .build();
    }

    /**
     * 基础 POST 聊天接口（处理单轮/多轮消息）
     * 调用示例：curl -X POST http://localhost:10000/openai/chat -H "Content-Type: application/json" -d '{"messages": [...]}'
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public String chat(@RequestBody ChatRequest request) {

        // 2构建模型服务需要的请求体（包含 model 和 messages）
        Map<String, Object> modelRequest = new HashMap<>();
        modelRequest.put("model", modelId); // 补充 model 字段
        // 转换 ChatRequest 的 messages 为模型服务需要的格式
        List<Map<String, String>> modelMessages = request.getMessages().stream()
                .map(msg -> {
                    Map<String, String> m = new HashMap<>();
                    m.put("role", msg.getRole());
                    m.put("content", msg.getContent());
                    return m;
                })
                .collect(Collectors.toList());
        modelRequest.put("messages", modelMessages);

        //  直接调用模型服务接口（替代原来的 chatClient.prompt()，避免格式转换问题）
        // 使用 RestTemplate 发送 POST 请求到模型服务的 /v1/chat/completions
        String modelUrl = openAiBaseUrl + "/v1/chat/completions"; // openAiBaseUrl 从配置注入（http://localhost:8000）
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + openAiApiKey); // 注入 api-key
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(modelRequest, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(modelUrl, requestEntity, Map.class);
            // 解析响应中的 content（不同服务格式可能不同，需调整）
            Map<String, Object> responseBody = response.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            log.error("模型调用失败", e);
            throw new RuntimeException("模型调用失败：" + e.getMessage());
        }
    }

    /**
     * 带会话 ID 的 POST 接口（支持多轮上下文）
     * 调用示例：curl -X POST "http://localhost:10000/openai/chat/with-chatid?chatId=xxx" -H "Content-Type: application/json" -d '{"messages": [...]}'
     */
    @PostMapping(value = "/with-chatid", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String chatWithChatId(
            @RequestBody ChatRequest request,
            @RequestParam String chatId) { // 会话唯一标识，用于关联上下文
        StringBuilder prompt = new StringBuilder();
        for (ChatRequest.Message msg : request.getMessages()) {
            prompt.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        // 传入 chatId 保持上下文连续性
        return openAiChatClient
                .prompt(prompt.toString())
                .advisors(advisors -> advisors
                        .param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100)) // 最多保留100条历史
                .call()
                .content();
    }

    /**
     * POST 流式响应接口（SSE 格式，适合实时展示）
     * 调用示例：curl -X POST http://localhost:10000/openai/chat/stream -H "Content-Type: application/json" -d '{"messages": [...]}'
     */
    @PostMapping(value = "/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest request) {
        StringBuilder prompt = new StringBuilder();
        for (ChatRequest.Message msg : request.getMessages()) {
            prompt.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        // 流式返回 SSE 格式数据（前端可逐段渲染）
        return openAiChatClient
                .prompt(prompt.toString())
                .stream()
                .content()
                .map(content -> ServerSentEvent.<String>builder()
                        .data(content) // 每条数据以 "data: 内容" 格式返回
                        .build());
    }
}