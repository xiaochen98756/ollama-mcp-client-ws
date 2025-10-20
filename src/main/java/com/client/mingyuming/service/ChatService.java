package com.client.mingyuming.service;

import com.client.mingyuming.util.LlmHttpUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工具 API 调用服务（复用优化版）
 */
@Slf4j
@Service
public class ChatService {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().setLenient().create();

    // 工具常量
    public static final String CURRENT_DATE_TOOL = "current-date-tool";
    public static final String CALCULATOR_TOOL = "calculator-tool";
    public static final String DATA_QUERY_TOOL = "data-query-tool";
    public static final String KNOWLEDGE_QA_TOOL = "knowledge-qa-tool";

    // 工具 API 映射
    private static final Map<String, String> TOOL_API_MAP = Map.of(
            "credit-card-tool", "/api/credit-card/monthly-bill",
            "exchange-rate-tool", "/api/exchange-rate",
            "utility-bill-tool", "/api/utility-bill/monthly-bill",
            "user-asset-tool", "/api/user/assets",
            "payment-order-tool", "/api/qr/create-payment-order"
    );

    // 错误提示
    private static final String API_TIMEOUT_MSG = "工具 API 调用超时";
    private static final String API_ERROR_MSG = "工具 API 调用失败";
    public static final String PARAM_ERROR_MSG = "工具参数缺失或格式错误";
    private static final String AUTH_ERROR_MSG = "工具鉴权失败，请检查 AppId/AppKey";
    public static final String SYSTEM_ERROR_MSG = "系统繁忙，请重试";

    // 配置参数
    @Value("${team.api.base-url}")
    private String teamApiBaseUrl;
    @Value("${team.api.app-id}")
    private String teamAppId;
    @Value("${team.api.app-key}")
    private String teamAppKey;

    // 模型参数
    @Value("${llm.sql-generate.base-url}")
    private String sqlBaseUrl;
    @Value("${llm.sql-generate.chat-id}")
    private String sqlChatId;
    @Value("${llm.sql-generate.session-id}")
    private String sqlSessionId;
    @Value("${llm.sql-generate.authorization}")
    private String sqlAuth;
    @Value("${llm.data-query.base-url}")
    private String dataQueryBaseUrl;
    @Value("${llm.data-query.chat-id}")
    private String dataQueryChatId;
    @Value("${llm.data-query.session-id}")
    private String dataQuerySessionId;
    @Value("${llm.data-query.authorization}")
    private String dataQueryAuth;
    @Value("${llm.final-result.base-url}")
    private String finalResultBaseUrl;
    @Value("${llm.final-result.chat-id}")
    private String finalResultChatId;
    @Value("${llm.final-result.session-id}")
    private String finalResultSessionId;
    @Value("${llm.final-result.authorization}")
    private String finalResultAuth;
    @Value("${llm.knowledge-chat.base-url}")
    private String knowledgeBaseUrl;
    @Value("${llm.knowledge-chat.chat-id}")
    private String knowledgeChatId;
    @Value("${llm.knowledge-chat.session-id}")
    private String knowledgeSessionId;
    @Value("${llm.knowledge-chat.authorization}")
    private String knowledgeAuth;

    // 服务和工具
    @Autowired
    private MysqlQueryService mysqlQueryService;
    @Autowired
    private LlmHttpUtil llmHttpUtil;
    @Autowired
    private ToolService toolService;
    @Autowired
    private ExecutorService executorService; // 注入全局线程池
    private final RestTemplate restTemplate;
    @Getter
    private final ToolCallback[] toolCallbacks;

    // 构造方法
    @Autowired
    public ChatService(List<McpSyncClient> mcpSyncClientList, RestTemplate restTemplate) {
        this.restTemplate = restTemplate;

        // 初始化 MCP 工具回调
        ToolCallbackProvider toolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClientList);
        this.toolCallbacks = (ToolCallback[]) toolCallbackProvider.getToolCallbacks();
        log.info("工具服务初始化完成");
    }

    /**
     * 调用工具 API（原工具调用入口）
     */
    public String callToolApi(String toolJson) {
        try {
            Map<String, Object> toolData = parseToolParams(toolJson);
            String toolName = (String) toolData.get("toolName");

            if ("none".equals(toolName)) {
                return (String) toolData.getOrDefault("message", "非工具调用类问题");
            }

            // 本地工具处理
            if (CURRENT_DATE_TOOL.equals(toolName)) {
                return "当前系统日期（东八区）：" + toolService.getCurrentDate();
            }
            if (CALCULATOR_TOOL.equals(toolName)) {
                String expression = (String) toolData.get("expression");
                return "计算结果：" + toolService.calculate(expression);
            }

            // API工具处理
            if (!TOOL_API_MAP.containsKey(toolName)) {
                throw new IllegalArgumentException("不支持的工具：" + toolName);
            }
            toolData.remove("toolName");
            if (toolData.isEmpty()) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：工具参数为空");
            }

            String apiPath = TOOL_API_MAP.get(toolName);
            String apiUrl = teamApiBaseUrl + apiPath;
            return callGetApi(apiUrl, toolData, teamAppId, teamAppKey);

        } catch (JsonSyntaxException e) {
            log.error("JSON解析失败：{}", toolJson, e);
            return PARAM_ERROR_MSG + "：JSON格式无效";
        } catch (HttpClientErrorException e) {
            log.error("工具API调用错误：{}", e.getStatusCode(), e);
            return e.getStatusCode().is4xxClientError()
                    ? (e.getMessage().contains("鉴权") ? AUTH_ERROR_MSG : PARAM_ERROR_MSG)
                    : API_ERROR_MSG;
        } catch (Exception e) {
            log.error("工具调用异常", e);
            return e.getMessage().contains("超时") ? API_TIMEOUT_MSG : SYSTEM_ERROR_MSG;
        }
    }

    /**
     * 根据大模型决策执行工具（HARD难度入口）
     */
    public String executeToolByDecision(String toolDecisionJson, String question, String content) {
        try {
            Map<String, Object> decision = parseToolParams(toolDecisionJson);
            String toolName = (String) decision.get("toolName");

            if ("none".equals(toolName)) {
                return (String) decision.getOrDefault("message", "非工具调用类问题");
            }

            // 执行对应工具
            switch (toolName) {
                case DATA_QUERY_TOOL:
                    return executeDataQueryTool(question, content);
                case KNOWLEDGE_QA_TOOL:
                    return executeKnowledgeQaTool(question);
                case CURRENT_DATE_TOOL:
                    return "当前系统日期（东八区）：" + toolService.getCurrentDate();
                case CALCULATOR_TOOL:
                    Map<String, Object> calcParams = (Map<String, Object>) decision.get("parameters");
                    String expression = (String) calcParams.get("expression");
                    return "计算结果：" + toolService.calculate(expression);
                case "credit-card-tool":
                case "exchange-rate-tool":
                case "utility-bill-tool":
                case "user-asset-tool":
                case "payment-order-tool":
                    return callApiTool(toolName, (Map<String, Object>) decision.get("parameters"));
                default:
                    throw new IllegalArgumentException("不支持的工具：" + toolName);
            }
        } catch (JsonSyntaxException e) {
            log.error("决策JSON解析失败：{}", toolDecisionJson, e);
            return PARAM_ERROR_MSG + "：决策JSON格式无效";
        } catch (Exception e) {
            log.error("工具执行异常", e);
            return SYSTEM_ERROR_MSG + "：" + e.getMessage();
        }
    }

    /**
     * 数据查询工具实现（复用大模型调用和线程池）
     */
    public String executeDataQueryTool(String question, String content) {
        try {
            String userQuestion = question + (content != null ? "\n补充信息：" + content : "");
            log.info("数据查询工具调用：{}", userQuestion);

            // 路径1：生成SQL并执行
            AtomicReference<String> executeSql = new AtomicReference<>("");
            Callable<String> sqlLocalTask = () -> {
                String sql = llmHttpUtil.call(
                        "SQL生成大模型",
                        sqlBaseUrl,
                        sqlChatId,
                        sqlSessionId,
                        sqlAuth,
                        userQuestion,
                        trimmedAnswer -> {
                            final String SQL_PREFIX = "```sql";
                            final String SQL_SUFFIX = "```";
                            int prefixEnd = trimmedAnswer.indexOf(SQL_PREFIX);
                            int suffixStart = trimmedAnswer.indexOf(SQL_SUFFIX, prefixEnd + SQL_PREFIX.length());
                            if (prefixEnd == -1 || suffixStart == -1) {
                                throw new RuntimeException("SQL格式错误");
                            }
                            return trimmedAnswer.substring(prefixEnd + SQL_PREFIX.length(), suffixStart).trim();
                        }
                );
                executeSql.set(sql);
                return mysqlQueryService.executeQuery(sql);
            };

            // 路径2：直接调用数据查询模型
            Callable<String> dataQueryModelTask = () -> llmHttpUtil.call(
                    "数据查询大模型",
                    dataQueryBaseUrl,
                    dataQueryChatId,
                    dataQuerySessionId,
                    dataQueryAuth,
                    userQuestion,
                    trimmedAnswer -> trimmedAnswer
            );

            // 并发执行
            List<Future<String>> futures = executorService.invokeAll(
                    Arrays.asList(sqlLocalTask, dataQueryModelTask),
                    30, TimeUnit.SECONDS
            );
            String result1 = futures.get(0).get();
            String result2 = futures.get(1).get();
            log.info("双路径结果：result1={}, result2={}", result1, result2);

            // 整合结果
            String finalQuestion = String.format(
                    "用户问题：%s\n结果1：%s, 执行SQL：%s\n结果2：%s",
                    userQuestion, result1, executeSql.get(), result2
            );
            return llmHttpUtil.call(
                    "数据比对拼接大模型",
                    finalResultBaseUrl,
                    finalResultChatId,
                    finalResultSessionId,
                    finalResultAuth,
                    finalQuestion,
                    trimmedAnswer -> trimmedAnswer
            );
        } catch (Exception e) {
            log.error("数据查询工具异常", e);
            return "数据查询工具执行失败：" + e.getMessage();
        }
    }

    /**
     * 知识问答工具实现（复用大模型调用）
     */
    public String executeKnowledgeQaTool(String question) {
        try {
            String firstAnswer = llmHttpUtil.call(
                    "知识问答大模型",
                    knowledgeBaseUrl,
                    knowledgeChatId,
                    knowledgeSessionId,
                    knowledgeAuth,
                    question,
                    trimmedAnswer -> trimmedAnswer
            );

            if (firstAnswer == null || firstAnswer.trim().isEmpty() || firstAnswer.contains("没找到答案")) {
                String secondAnswer = llmHttpUtil.call(
                        "知识问答大模型（二次）",
                        knowledgeBaseUrl,
                        knowledgeChatId,
                        knowledgeSessionId,
                        knowledgeAuth,
                        question,
                        trimmedAnswer -> trimmedAnswer
                );
                return (secondAnswer == null || secondAnswer.contains("没找到答案")) ? "我没找到答案" : secondAnswer;
            } else {
                return firstAnswer;
            }
        } catch (Exception e) {
            log.error("知识问答工具异常：{}", question, e);
            return "我没找到答案";
        }
    }

    /**
     * 通用工具参数解析（复用）
     */
    private Map<String, Object> parseToolParams(String toolJson) {
        Map<String, Object> toolData = gson.fromJson(toolJson, Map.class);
        if (toolData == null) {
            throw new IllegalArgumentException(PARAM_ERROR_MSG + "：JSON格式错误");
        }
        String toolName = (String) toolData.get("toolName");
        if (toolName == null || toolName.isEmpty()) {
            throw new IllegalArgumentException(PARAM_ERROR_MSG + "：缺少toolName");
        }
        return toolData;
    }

    /**
     * 调用API工具（复用）
     */
    private String callApiTool(String toolName, Map<String, Object> parameters) {
        if (!TOOL_API_MAP.containsKey(toolName)) {
            throw new IllegalArgumentException("不支持的API工具：" + toolName);
        }
        if (parameters == null || parameters.isEmpty()) {
            throw new IllegalArgumentException(PARAM_ERROR_MSG + "：参数为空");
        }

        String apiPath = TOOL_API_MAP.get(toolName);
        String apiUrl = teamApiBaseUrl + apiPath;
        return callGetApi(apiUrl, parameters, teamAppId, teamAppKey);
    }

    /**
     * GET请求调用工具API（复用）
     */
    private String callGetApi(String apiUrl, Map<String, Object> params, String appId, String appKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-App-Id", appId);
        headers.set("X-App-Key", appKey);

        MultiValueMap<String, String> urlParams = new LinkedMultiValueMap<>();
        params.forEach((key, value) -> urlParams.add(key, value.toString()));

        String fullUrl = buildGetUrl(apiUrl, urlParams);
        log.info("调用工具API：{}", fullUrl);

        Map<String, Object> response = restTemplate.getForObject(fullUrl, Map.class);
        return formatResponse(response);
    }

    /**
     * 构建GET请求URL（复用）
     */
    private String buildGetUrl(String baseUrl, MultiValueMap<String, String> params) {
        if (params.isEmpty()) {
            return baseUrl;
        }
        StringBuilder urlBuilder = new StringBuilder(baseUrl).append("?");
        params.forEach((key, values) -> values.forEach(value -> urlBuilder.append(key).append("=").append(value).append("&")));
        return urlBuilder.substring(0, urlBuilder.length() - 1);
    }

    /**
     * 格式化工具响应（复用）
     */
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
            String usageUnit = switch ((String) response.get("utility_type")) {
                case "electricity" -> "kWh（度）";
                case "water" -> "m³（立方米）";
                case "gas" -> "m³（立方米）";
                default -> "";
            };
            return String.format("水电煤账单（%s-%s）：户号=%s，用量=%.2f%s，金额=%.2f%s，状态=%s",
                    response.get("bill_month"), response.get("utility_type"),
                    response.get("household_id"), response.get("usage_amount"),
                    usageUnit, response.get("bill_amount"),
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