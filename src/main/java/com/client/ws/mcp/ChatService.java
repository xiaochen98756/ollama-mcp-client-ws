package com.client.ws.mcp;

import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
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

    private final ChatClient chatClient;
    private final RestTemplate restTemplate;
    @Getter
    private final ToolCallback[] toolCallbacks;

    // -------------------------- 1. 核心配置（比赛时仅需修改这里） --------------------------
    // 组委会 API 基础地址（Mock 用本地地址，比赛时替换为组委会真实地址：http://api.example.com:30000）
    private static final String API_BASE_URL = "http://localhost:10000";
    // 组委会分配的鉴权信息（Mock 用 VALID_APP_ID/VALID_APP_KEY，比赛时替换为真实值）
    private static final String APP_ID = "team_123";
    private static final String APP_KEY = "key_456abc";
    // 工具名 → API 路径映射（与文档完全一致）
    private static final Map<String, String> TOOL_API_MAP = Map.of(
            "credit-card-tool", "/api/credit-card/monthly-bill",       // 信用卡账单
            "exchange-rate-tool", "/api/exchange-rate",                // 汇率服务
            "utility-bill-tool", "/api/utility-bill/monthly-bill",     // 水电煤账单
            "user-asset-tool", "/api/user/assets",                     // 用户资产
            "payment-order-tool", "/api/qr/create-payment-order"       // 支付订单
    );

    // -------------------------- 2. 系统提示（引导模型输出 GET 参数格式） --------------------------
    private static final String SYSTEM_PROMPT = "你是组委会 API 工具调用助手，严格遵守以下规则：" +
            "1. 先判断问题对应工具，再输出「工具名:参数Map」，仅保留这部分内容，不要多余文字；" +
            "2. 工具与参数要求：" +
            "   - 信用卡账单（credit-card-tool）：参数必须含 cardNumber(信用卡号)、month(YYYY-MM)，如 {\"cardNumber\":\"6211111111111111\",\"month\":\"2025-09\"}；" +
            "   - 汇率服务（exchange-rate-tool）：参数必须含 fromCurrency、toCurrency，可选 amount（默认1），如 {\"fromCurrency\":\"USD\",\"toCurrency\":\"CNY\",\"amount\":100}；" +
            "   - 水电煤账单（utility-bill-tool）：参数必须含 householdId、month(YYYY-MM)，可选 utilityType（默认electricity），如 {\"householdId\":\"BJ001234567\",\"month\":\"2025-09\",\"utilityType\":\"water\"}；" +
            "   - 用户资产（user-asset-tool）：参数必须含 customerId(身份证)，可选 assetType（默认card），如 {\"customerId\":\"110101199003072845\",\"assetType\":\"household\"}；" +
            "   - 支付订单（payment-order-tool）：参数必须含 merchantId、orderId，可选 amount（默认0），如 {\"merchantId\":\"M123456\",\"orderId\":\"ORD2025001\",\"amount\":100.50}；" +
            "3. 非工具问题直接输出「非工具调用类问题」。";

    // -------------------------- 3. 标准化错误提示 --------------------------
    private static final String API_TIMEOUT_MSG = "工具 API 调用超时";
    private static final String API_ERROR_MSG = "工具 API 调用失败";
    private static final String PARAM_ERROR_MSG = "工具参数缺失或格式错误";
    private static final String AUTH_ERROR_MSG = "工具鉴权失败，请检查 AppId/AppKey";
    private static final String SYSTEM_ERROR_MSG = "系统繁忙，请重试";

    // -------------------------- 构造方法 --------------------------
    public ChatService(OllamaChatModel ollamaChatModel,
                       List<McpSyncClient> mcpSyncClientList,
                       RestTemplate restTemplate) {
        this.restTemplate = restTemplate;

        // 初始化 MCP 工具（兼容后续扩展，当前主要用 HTTP 工具）
        ToolCallbackProvider toolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClientList);
        this.toolCallbacks = Objects.nonNull(toolCallbackProvider.getToolCallbacks())
                ? (ToolCallback[]) toolCallbackProvider.getToolCallbacks()
                : new ToolCallback[0];

        // 初始化 ChatClient（绑定工具逻辑）
        this.chatClient = ChatClient.builder(ollamaChatModel)
                .defaultTools(toolCallbacks)
                .build();

        log.info("组委会 API 工具初始化完成，可用工具：{}", TOOL_API_MAP.keySet());
    }

    // -------------------------- 核心调用方法 --------------------------
    public String askQuestion(SystemMessage systemMessage, UserMessage userMessage) {
        try {
            // 1. 生成工具调用指令（工具名:参数Map）
            String toolCmd = generateToolCommand(systemMessage, userMessage);
            log.info("模型生成工具指令：{}", toolCmd);

            // 2. 过滤非工具调用
            if (toolCmd.contains("非工具调用类问题")) {
                return toolCmd;
            }

            // 3. 解析指令（拆分工具名和参数）
            String[] cmdParts = toolCmd.split(":", 2);
            if (cmdParts.length != 2) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：" + toolCmd);
            }
            String toolName = cmdParts[0].trim();
            Map<String, Object> params = JSON.parseObject(cmdParts[1].trim(), Map.class);

            // 4. 调用组委会 API
            String apiPath = TOOL_API_MAP.get(toolName);
            if (apiPath == null) {
                return "未支持的工具：" + toolName;
            }
            String apiUrl = API_BASE_URL + apiPath;
            return callGetApi(apiUrl, params);

        } catch (HttpClientErrorException e) {
            // 鉴权失败（401/403）或参数错误（400）
            log.error("API 调用错误（状态码：{}）", e.getStatusCode(), e);
            return e.getStatusCode().is4xxClientError()
                    ? (e.getMessage().contains("鉴权") ? AUTH_ERROR_MSG : PARAM_ERROR_MSG)
                    : API_ERROR_MSG;
        } catch (Exception e) {
            log.error("工具调用异常", e);
            return e.getMessage().contains("超时") ? API_TIMEOUT_MSG : SYSTEM_ERROR_MSG;
        }
    }

    // -------------------------- 辅助方法：生成工具指令 --------------------------
    private String generateToolCommand(SystemMessage systemMessage, UserMessage userMessage) {
        // 合并用户提示和系统提示（确保模型按规则输出）
        String fullPrompt = systemMessage.getText() + "\n" + SYSTEM_PROMPT;
        return chatClient.prompt()
                .messages(new SystemMessage(fullPrompt), userMessage)
                .call()
                .content();
    }

    // -------------------------- 辅助方法：调用 GET API（带鉴权头+URL参数） --------------------------
    private String callGetApi(String apiUrl, Map<String, Object> params) {
        // 1. 构建鉴权头
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-App-Id", APP_ID);
        headers.set("X-App-Key", APP_KEY);

        // 2. 构建 GET 参数（URL 拼接用）
        MultiValueMap<String, String> urlParams = new LinkedMultiValueMap<>();
        params.forEach((key, value) -> urlParams.add(key, value.toString()));

        // 3. 构造请求（GET 方法需用 HttpEntity 传递头，参数通过 URL 拼接）
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(headers);
        String fullUrl = buildGetUrl(apiUrl, urlParams);
        log.info("调用 GET API：{}", fullUrl);

        // 4. 发起请求并解析响应
        Map<String, Object> response = restTemplate.getForObject(fullUrl, Map.class);

        // 5. 格式化响应结果（按比赛要求返回关键信息，避免冗长）
        return formatResponse(response);
    }

    // -------------------------- 辅助方法：拼接 GET URL 参数 --------------------------
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
        // 移除最后一个 "&"
        return urlBuilder.substring(0, urlBuilder.length() - 1);
    }

    // -------------------------- 辅助方法：格式化响应（突出关键信息） --------------------------
    private String formatResponse(Map<String, Object> response) {
        if (response.containsKey("card_number")) {
            // 信用卡账单：返回卡号、总金额、状态
            return String.format("信用卡账单（%s）：卡号=%s，总金额=%.2f%s，状态=%s，截止日期=%s",
                    response.get("bill_month"),
                    response.get("card_number"),
                    response.get("total_amount"),
                    response.get("currency"),
                    response.get("payment_status"),
                    response.get("due_date"));
        } else if (response.containsKey("from_currency")) {
            // 汇率服务：返回转换结果
            return String.format("汇率转换：%.2f%s = %.2f%s（汇率：%s），更新时间=%s",
                    response.get("amount"),
                    response.get("from_currency"),
                    response.get("converted_amount"),
                    response.get("to_currency"),
                    response.get("rate"),
                    response.get("timestamp"));
        } else if (response.containsKey("utility_type")) {
            // 水电煤账单：返回户号、金额、用量
            return String.format("水电煤账单（%s-%s）：户号=%s，用量=%.2f%s，金额=%.2f%s，状态=%s",
                    response.get("bill_month"),
                    response.get("utility_type"),
                    response.get("household_id"),
                    response.get("usage_amount"),
                    response.get("usage_unit"),
                    response.get("bill_amount"),
                    response.get("currency"),
                    response.get("payment_status"));
        } else if (response.containsKey("cards") || response.containsKey("households")) {
            // 用户资产：返回资产数量
            String assetType = response.containsKey("cards") ? "信用卡" : "房产";
            int count = response.containsKey("cards")
                    ? ((List<?>) response.get("cards")).size()
                    : ((List<?>) response.get("households")).size();
            return String.format("用户资产（%s）：用户ID=%s，共%s个%s资产",
                    assetType,
                    response.get("customer_id"),
                    count,
                    assetType);
        } else if (response.containsKey("payment_order_id")) {
            // 支付订单：返回订单ID、状态
            return String.format("支付订单创建成功：订单ID=%s，商户号=%s，金额=%.2f%s，状态=%s，过期时间=%s",
                    response.get("payment_order_id"),
                    response.get("merchant_id"),
                    response.get("amount"),
                    "CNY", // 文档默认货币
                    response.get("payment_status"),
                    response.get("expire_time"));
        } else {
            // 未知响应格式：返回原始数据（便于调试）
            return "工具响应：" + response.toString();
        }
    }

    // -------------------------- 重载方法：默认使用系统提示 --------------------------
    public String askQuestion(Message message) {
        return askQuestion(new SystemMessage(""), new UserMessage(message.getText()));
    }
}