package com.client.mingyuming.mcp;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具 API 调用服务（仅处理工具请求）
 */
@Slf4j
@Service
public class ChatService {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    // 工具 API 配置（从配置文件注入）
    @Value("${team.api.base-url}")
    private String teamApiBaseUrl;

    @Value("${team.api.app-id}")
    private String teamAppId;

    @Value("${team.api.app-key}")
    private String teamAppKey;

    // 工具 API 映射
    private static final Map<String, String> TOOL_API_MAP = Map.of(
            "credit-card-tool", "/mock/credit-card/monthly-bill",
            "exchange-rate-tool", "/mock/exchange-rate",
            "utility-bill-tool", "/mock/utility-bill/monthly-bill",
            "user-asset-tool", "/mock/user/assets",
            "payment-order-tool", "/mock/qr/create-payment-order"
    );

    // 错误提示常量
    private static final String API_TIMEOUT_MSG = "工具 API 调用超时";
    private static final String API_ERROR_MSG = "工具 API 调用失败";
    private static final String PARAM_ERROR_MSG = "工具参数缺失或格式错误";
    private static final String AUTH_ERROR_MSG = "工具鉴权失败，请检查 AppId/AppKey";
    private static final String SYSTEM_ERROR_MSG = "系统繁忙，请重试";

    private final RestTemplate restTemplate;
    @Getter
    private final ToolCallback[] toolCallbacks;

    // 构造方法：初始化工具回调和 RestTemplate
    public ChatService(List<McpSyncClient> mcpSyncClientList, RestTemplate restTemplate) {
        this.restTemplate = restTemplate;

        // 初始化 MCP 工具回调
        ToolCallbackProvider toolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClientList);
        this.toolCallbacks = Objects.nonNull(toolCallbackProvider.getToolCallbacks())
                ? (ToolCallback[]) toolCallbackProvider.getToolCallbacks()
                : new ToolCallback[0];

        log.info("工具 API 服务初始化完成" + TOOL_API_MAP);
    }

    /**
     * 调用工具 API 执行大模型生成的指令
     * @param toolCmd 大模型生成的工具指令（格式：工具名:{"参数":...}）
     * @return 工具 API 响应结果
     */
    /**
     * 调用工具 API（通过JSON中的toolName字段识别工具）
     *
     * @param toolJson 大模型输出的完整JSON（包含toolName和参数）
     */
    public String callToolApi(String toolJson) {
        try {
            // 1. 解析JSON为Map（使用fastjson2）
            Map<String, Object> toolData = gson.fromJson(toolJson, Map.class);
            if (toolData == null) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：JSON格式错误");
            }

            // 2. 提取toolName并校验
            String toolName = (String) toolData.get("toolName");
            if (toolName == null || toolName.isEmpty()) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：缺少toolName字段");
            }

            // 3. 处理非工具调用情况
            if ("none".equals(toolName)) {
                return (String) toolData.getOrDefault("message", "非工具调用类问题");
            }

            // 4. 校验工具是否支持
            if (!TOOL_API_MAP.containsKey(toolName)) {
                throw new IllegalArgumentException("不支持的工具：" + toolName);
            }

            // 5. 提取工具参数（移除toolName，保留其他字段）
            toolData.remove("toolName"); // 避免参数中包含toolName
            if (toolData.isEmpty()) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：工具参数为空");
            }

            // 6. 调用工具API
            String apiPath = TOOL_API_MAP.get(toolName);
            String apiUrl = teamApiBaseUrl + apiPath;
            return callGetApi(apiUrl, toolData, teamAppId, teamAppKey);

        }  catch (HttpClientErrorException e) {
            log.error("工具API调用错误（状态码：{}）", e.getStatusCode(), e);
            return e.getStatusCode().is4xxClientError()
                    ? (e.getMessage().contains("鉴权") ? AUTH_ERROR_MSG : PARAM_ERROR_MSG)
                    : API_ERROR_MSG;
        } catch (Exception e) {
            log.error("工具调用异常", e);
            return e.getMessage().contains("超时") ? API_TIMEOUT_MSG : SYSTEM_ERROR_MSG;
        }
    }

    // 调用 GET 类型工具 API
    private String callGetApi(String apiUrl, Map<String, Object> params, String appId, String appKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-App-Id", appId);
        headers.set("X-App-Key", appKey);

        MultiValueMap<String, String> urlParams = new LinkedMultiValueMap<>();
        params.forEach((key, value) -> urlParams.add(key, value.toString()));

        String fullUrl = buildGetUrl(apiUrl, urlParams);
        log.info("调用工具 API：{}", fullUrl);

        Map<String, Object> response = restTemplate.getForObject(fullUrl, Map.class);
        return formatResponse(response);
    }

    // 拼接 GET 请求 URL
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

    // 格式化工具 API 响应
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
}