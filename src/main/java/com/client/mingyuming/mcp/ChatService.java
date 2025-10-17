package com.client.mingyuming.mcp;

import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel; // 保持使用 OpenAiChatModel
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class ChatService {

    // 从配置文件读取参数
    @Value("${spring.ai.openai.base-url}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.chat.options.model}") // 注意：1.0.0-M7 用 chat 配置模型
    private String llmModelId;

    private final ChatClient chatClient;

    private final RestTemplate restTemplate;
    @Getter
    private final ToolCallback[] toolCallbacks;

    // 工具API映射（不变）
    private static final Map<String, String> TOOL_API_MAP = Map.of(
            "credit-card-tool", "/mock/credit-card/monthly-bill",
            "exchange-rate-tool", "/mock/exchange-rate",
            "utility-bill-tool", "/mock/utility-bill/monthly-bill",
            "user-asset-tool", "/mock/user/assets",
            "payment-order-tool", "/mock/qr/create-payment-order"
    );

    // 系统提示（不变）
    private static final String SYSTEM_PROMPT = "你是组委会API工具调用助手，严格遵守以下规则：" +
            "1. 必须输出「工具名:参数JSON」格式，仅保留这部分内容，不要任何多余文字；" +
            "2. 参数JSON必须是标准格式：用{}包裹，键名和字符串值用双引号，无多余逗号；" +
            "3. 各工具参数要求：" +
            "   - 信用卡账单（credit-card-tool）：cardNumber、month（YYYY-MM）；" +
            "   - 汇率服务（exchange-rate-tool）：fromCurrency、toCurrency，可选amount；" +
            "   - 水电煤账单（utility-bill-tool）：householdId、month，可选utilityType；" +
            "   - 用户资产（user-asset-tool）：customerId，可选assetType；" +
            "   - 支付订单（payment-order-tool）：merchantId、orderId，可选amount；" +
            "4. 非工具问题直接输出「非工具调用类问题」。";

    // 错误提示（不变）
    private static final String API_TIMEOUT_MSG = "工具 API 调用超时";
    private static final String API_ERROR_MSG = "工具 API 调用失败";
    private static final String PARAM_ERROR_MSG = "工具参数缺失或格式错误";
    private static final String AUTH_ERROR_MSG = "工具鉴权失败，请检查 AppId/AppKey";
    private static final String SYSTEM_ERROR_MSG = "系统繁忙，请重试";

    // 构造方法（使用 OpenAiChatModel，适配 1.0.0-M7）
    public ChatService(OpenAiChatModel openAiChatModel,  // 保持使用 ChatModel
                       List<McpSyncClient> mcpSyncClientList,
                       RestTemplate restTemplate) {
        this.restTemplate = restTemplate;

        // 初始化 MCP 工具（不变）
        ToolCallbackProvider toolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClientList);
        this.toolCallbacks = Objects.nonNull(toolCallbackProvider.getToolCallbacks())
                ? (ToolCallback[]) toolCallbackProvider.getToolCallbacks()
                : new ToolCallback[0];

        // 初始化 ChatClient：绑定 ChatModel，但配置为兼容 Completion 接口
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultTools(toolCallbacks)
                .defaultOptions(ChatOptions.builder()
                        .model(llmModelId)  // 模型ID（./generate/Qwen3-0.6B-Q8_0.gguf）
                        .temperature(0.1)
                        .maxTokens(1024)
                        .build())
                .build();
        log.info("当前模型名：{}", openAiChatModel.getDefaultOptions().getModel());
        log.info("本地 vllm 模型（OpenAI 兼容）初始化完成");
    }

    // 核心调用方法（不变）
    public String askQuestion(SystemMessage systemMessage, UserMessage userMessage) {
        try {
            String toolCmd = generateToolCommand(systemMessage, userMessage);
            log.info("模型生成工具指令：{}", toolCmd);

            if (toolCmd.contains("非工具调用类问题")) {
                return toolCmd;
            }

            String[] cmdParts = toolCmd.split(":", 2);
            if (cmdParts.length != 2) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：" + toolCmd);
            }

            String rawToolName = cmdParts[0].trim();
            String toolName = rawToolName.replaceAll("[^a-zA-Z0-9_-]", "");

            if (toolName.isEmpty() || !TOOL_API_MAP.containsKey(toolName)) {
                throw new IllegalArgumentException("无效的工具名：" + rawToolName);
            }
            String paramJson = cmdParts[1].trim()
                    .replaceAll("^\\{+", "{")
                    .replaceAll("}+$", "}")
                    .replaceAll(",\\s*}", "}");
            Map<String, Object> params = JSON.parseObject(paramJson, Map.class);

            // 调用组委会 API（可改为 @Value 注入）
            String apiBaseUrl = "http://localhost:10000";
            String appId = "team_123";
            String appKey = "key_456abc";
            String apiPath = TOOL_API_MAP.get(toolName);
            if (apiPath == null) {
                return "未支持的工具：" + toolName;
            }
            String apiUrl = apiBaseUrl + apiPath;
            return callGetApi(apiUrl, params, appId, appKey);

        } catch (HttpClientErrorException e) {
            log.error("API 调用错误（状态码：{}）", e.getStatusCode(), e);
            return e.getStatusCode().is4xxClientError()
                    ? (e.getMessage().contains("鉴权") ? AUTH_ERROR_MSG : PARAM_ERROR_MSG)
                    : API_ERROR_MSG;
        } catch (Exception e) {
            log.error("工具调用异常", e);
            return e.getMessage().contains("超时") ? API_TIMEOUT_MSG : SYSTEM_ERROR_MSG;
        }
    }

    // 核心修改：生成工具指令时用 prompt() 传递纯文本（模拟 Completion 接口）
    private String generateToolCommand(SystemMessage systemMessage, UserMessage userMessage) {
        // 1. 拼接纯文本 prompt（系统提示 + 用户问题）
        String userSystemPrompt = (systemMessage != null && systemMessage.getText() != null)
                ? systemMessage.getText()
                : "";
        // 关键：用纯文本格式，符合 vllm Completion 接口的 "prompt" 字段要求
        String fullPrompt = String.format("系统提示：%s\n用户问题：%s\n请严格按照系统提示输出结果：",
                userSystemPrompt + SYSTEM_PROMPT,
                userMessage.getText());
        log.info("向本地模型发送的完整 prompt：{}", fullPrompt);

        // 2. 调用模型：用 prompt() 而非 messages()，避免生成 messages 数组格式
        return chatClient
                .prompt(fullPrompt)  // 传递纯文本 prompt，适配 Completion 接口
                .options(ChatOptions.builder()
                        .model(llmModelId)
                        .temperature(0.1)
                        .maxTokens(1024)
                        .build())
                .call()
                .content()
                .trim();
    }

    // 调用 GET API 方法（不变）
    private String callGetApi(String apiUrl, Map<String, Object> params, String appId, String appKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-App-Id", appId);
        headers.set("X-App-Key", appKey);

        MultiValueMap<String, String> urlParams = new LinkedMultiValueMap<>();
        params.forEach((key, value) -> urlParams.add(key, value.toString()));

        String fullUrl = buildGetUrl(apiUrl, urlParams);
        log.info("调用 GET API：{}", fullUrl);

        Map<String, Object> response = restTemplate.getForObject(fullUrl, Map.class);
        return formatResponse(response);
    }

    // 拼接 URL 参数（不变）
    private String buildGetUrl(String baseUrl, MultiValueMap<String, String> params) {
        if (params.isEmpty()) {
            return baseUrl;
        }
        StringBuilder urlBuilder = new StringBuilder(baseUrl).append("?");
        params.forEach((key, values) -> {
            for (String value : values) {
                urlBuilder.append(key).append("=").append(value).append("&");
            }
        });
        return urlBuilder.substring(0, urlBuilder.length() - 1);
    }

    // 格式化响应（不变，增加空指针防护）
    private String formatResponse(Map<String, Object> response) {
        if (response == null) {
            return SYSTEM_ERROR_MSG;
        }
        if (response.containsKey("card_number")) {
            return String.format("信用卡账单（%s）：卡号=%s，总金额=%.2f%s，状态=%s，截止日期=%s",
                    response.get("bill_month"), response.get("card_number"),
                    response.get("total_amount"), response.get("currency"),
                    response.get("payment_status"), response.get("due_date"));
        } else if (response.containsKey("from_currency")) {
            return String.format("汇率转换：%.2f%s = %.2f%s（汇率：%s），更新时间=%s",
                    response.get("amount"), response.get("from_currency"),
                    response.get("converted_amount"), response.get("to_currency"),
                    response.get("rate"), response.get("timestamp"));
        } else if (response.containsKey("utility_type")) {
            return String.format("水电煤账单（%s-%s）：户号=%s，用量=%.2f%s，金额=%.2f%s，状态=%s",
                    response.get("bill_month"), response.get("utility_type"),
                    response.get("household_id"), response.get("usage_amount"),
                    response.get("usage_unit"), response.get("bill_amount"),
                    response.get("currency"), response.get("payment_status"));
        } else if (response.containsKey("cards") || response.containsKey("households")) {
            String assetType = response.containsKey("cards") ? "信用卡" : "房产";
            int count = response.containsKey("cards")
                    ? ((List<?>) response.get("cards")).size()
                    : ((List<?>) response.get("households")).size();
            return String.format("用户资产（%s）：用户ID=%s，共%s个%s资产",
                    assetType, response.get("customer_id"), count, assetType);
        } else if (response.containsKey("payment_order_id")) {
            return String.format("支付订单创建成功：订单ID=%s，商户号=%s，金额=%.2f%s，状态=%s，过期时间=%s",
                    response.get("payment_order_id"), response.get("merchant_id"),
                    response.get("amount"), "CNY",
                    response.get("payment_status"), response.get("expire_time"));
        } else {
            return "工具响应：" + response.toString();
        }
    }

    // 重载方法（不变）
    public String askQuestion(Message message) {
        return askQuestion(new SystemMessage(""), new UserMessage(message.getText()));
    }
}