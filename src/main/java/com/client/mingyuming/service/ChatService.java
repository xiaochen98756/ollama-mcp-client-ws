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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工具 API 调用服务（仅处理工具请求）
 */
@Slf4j
@Service
public class ChatService {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().setLenient().create();
    // 工具 API 配置（从配置文件注入）
    @Value("${team.api.base-url}")
    private String teamApiBaseUrl;

    @Value("${team.api.app-id}")
    private String teamAppId;

    @Value("${team.api.app-key}")
    private String teamAppKey;

    // 注入所需服务和配置（需要从ExamController迁移依赖）
    @Autowired
    private MysqlQueryService mysqlQueryService;
    @Autowired
    private LlmHttpUtil llmHttpUtil;
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
    // 线程池（用于数据查询的双路径并发）
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    @Autowired
    ToolService toolService;
    // 新增：本地工具标识（需同步告知大模型）
    public static final String CURRENT_DATE_TOOL = "current-date-tool"; // 获取当前日期工具
    public static final String CALCULATOR_TOOL = "calculator-tool";     // 计算器工具

    // 新增：数据查询和知识问答工具标识
    public static final String DATA_QUERY_TOOL = "data-query-tool"; // 数据查询工具
    public static final String KNOWLEDGE_QA_TOOL = "knowledge-qa-tool"; // 知识问答工具

    // 扩展工具API映射（包含所有工具：原有API工具、本地工具、新增工具）
    private static final Map<String, String> ALL_TOOLS_MAP = Map.ofEntries(
            // 原有API工具
            Map.entry("credit-card-tool", "/api/credit-card/monthly-bill"),
            Map.entry("exchange-rate-tool", "/api/exchange-rate"),
            Map.entry("utility-bill-tool", "/api/utility-bill/monthly-bill"),
            Map.entry("user-asset-tool", "/api/user/assets"),
            Map.entry("payment-order-tool", "/api/qr/create-payment-order"),
            // 本地工具
            Map.entry(CURRENT_DATE_TOOL, "获取当前日期工具"),
            Map.entry(CALCULATOR_TOOL, "计算器工具"),
            // 数据查询和知识问答工具
            Map.entry(DATA_QUERY_TOOL, "数据查询工具（生成SQL并执行）"),
            Map.entry(KNOWLEDGE_QA_TOOL, "知识问答工具（回答常识性问题）")
    );

    // 工具 API 映射
    private static final Map<String, String> TOOL_API_MAP = Map.of(
            "credit-card-tool", "/api/credit-card/monthly-bill",
            "exchange-rate-tool", "/api/exchange-rate",
            "utility-bill-tool", "/api/utility-bill/monthly-bill",
            "user-asset-tool", "/api/user/assets",
            "payment-order-tool", "/api/qr/create-payment-order"
    );
    // 本地工具 API 映射
    private static final Map<String, String> TOOL_CURRENT_MAP = Map.of(
            "current-date-tool", "获取当前日期工具",
            "calculator-tool", "计算器工具"
    );
    // 错误提示常量
    private static final String API_TIMEOUT_MSG = "工具 API 调用超时";
    private static final String API_ERROR_MSG = "工具 API 调用失败";
    public static final String PARAM_ERROR_MSG = "工具参数缺失或格式错误";
    private static final String AUTH_ERROR_MSG = "工具鉴权失败，请检查 AppId/AppKey";
    public static final String SYSTEM_ERROR_MSG = "系统繁忙，请重试";

    private final RestTemplate restTemplate;
    @Getter
    private final ToolCallback[] toolCallbacks;

    // 构造方法：初始化工具回调和 RestTemplate
    public ChatService(List<McpSyncClient> mcpSyncClientList, RestTemplate restTemplate) {
        this.restTemplate = restTemplate;

        // 初始化 MCP 工具回调
        ToolCallbackProvider toolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClientList);
        toolCallbackProvider.getToolCallbacks();
        this.toolCallbacks = (ToolCallback[]) toolCallbackProvider.getToolCallbacks();
        log.info("工具服务初始化完成!\nAPI工具:{}\n本地工具{}", gson.toJson(TOOL_API_MAP), gson.toJson(TOOL_CURRENT_MAP));
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
            // 4. 新增：优先处理本地工具
            // 4.1 调用当前日期工具
            if (CURRENT_DATE_TOOL.equals(toolName)) {
                String currentDate = toolService.getCurrentDate();
                return "当前系统日期（东八区）：" + currentDate;
            }

            // 4.2 调用计算器工具（需要表达式参数）
            if (CALCULATOR_TOOL.equals(toolName)) {
                String expression = (String) toolData.get("expression"); // 从参数中获取表达式
                return "计算结果：" + toolService.calculate(expression);
            }
            // 5. 校验工具是否支持
            if (!TOOL_API_MAP.containsKey(toolName)) {
                throw new IllegalArgumentException("不支持的工具：" + toolName);
            }

            // 6. 提取工具参数（移除toolName，保留其他字段）
            toolData.remove("toolName"); // 避免参数中包含toolName
            if (toolData.isEmpty()) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：工具参数为空");
            }

            // 7. 调用工具API
            String apiPath = TOOL_API_MAP.get(toolName);
            String apiUrl = teamApiBaseUrl + apiPath;
            return callGetApi(apiUrl, toolData, teamAppId, teamAppKey);

        } catch (JsonSyntaxException e) {
            // 关键：打印原始输入和预处理后的内容，便于定位问题
            log.error("JSON 解析失败！ 预处理后：{}", toolJson, e);
            return PARAM_ERROR_MSG + "：JSON格式无效（未完全解析，可能包含多余内容）";
        } catch (HttpClientErrorException e) {
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
            // 根据 utility_type 确定用量单位（匹配工具文档和测试结果）
            String usageUnit = switch ((String) response.get("utility_type")) {
                case "electricity" -> "kWh（度）";
                case "water" -> "m³（立方米）";
                case "gas" -> "m³（立方米）";
                default -> "";
            };
            return String.format("水电煤账单（%s-%s）：户号=%s，用量=%.2f%s，金额=%.2f%s，状态=%s",
                    response.get("bill_month"), response.get("utility_type"),
                    response.get("household_id"), response.get("usage_amount"),
                    usageUnit, // 使用动态匹配的单位，不再依赖不存在的字段
                    response.get("bill_amount"),
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
    /**
     * 根据大模型的决策结果执行对应工具
     * @param toolDecisionJson 大模型返回的决策JSON（包含toolName和parameters）
     * @param question 用户原始问题
     * @param content 补充信息
     * @return 工具执行结果
     */
    public String executeToolByDecision(String toolDecisionJson, String question, String content) {
        try {
            // 解析决策结果
            Map<String, Object> decision = gson.fromJson(toolDecisionJson, Map.class);
            if (decision == null) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：工具决策JSON格式错误");
            }

            String toolName = (String) decision.get("toolName");
            if (toolName == null || toolName.isEmpty()) {
                throw new IllegalArgumentException(PARAM_ERROR_MSG + "：缺少toolName字段");
            }

            // 处理无工具调用的情况
            if ("none".equals(toolName)) {
                return (String) decision.getOrDefault("message", "非工具调用类问题");
            }

            // 执行对应工具
            switch (toolName) {
                case DATA_QUERY_TOOL:
                    // 数据查询工具：使用用户问题和补充信息作为参数
                    return executeDataQueryTool(question, content);
                case KNOWLEDGE_QA_TOOL:
                    // 知识问答工具：使用用户问题作为参数
                    return executeKnowledgeQaTool(question);
                case CURRENT_DATE_TOOL:
                    // 本地日期工具：无参数
                    return "当前系统日期（东八区）：" + toolService.getCurrentDate();
                case CALCULATOR_TOOL:
                    // 本地计算器工具：提取表达式参数
                    Map<String, Object> calcParams = (Map<String, Object>) decision.get("parameters");
                    String expression = (String) calcParams.get("expression");
                    return "计算结果：" + toolService.calculate(expression);
                case "credit-card-tool":
                case "exchange-rate-tool":
                case "utility-bill-tool":
                case "user-asset-tool":
                case "payment-order-tool":
                    // 原有API工具：复用callToolApi的参数处理逻辑
                    return callApiTool(toolName, (Map<String, Object>) decision.get("parameters"));
                default:
                    throw new IllegalArgumentException("不支持的工具：" + toolName);
            }
        } catch (JsonSyntaxException e) {
            log.error("工具决策JSON解析失败：{}", toolDecisionJson, e);
            return PARAM_ERROR_MSG + "：工具决策JSON格式无效";
        } catch (Exception e) {
            log.error("工具执行异常", e);
            return SYSTEM_ERROR_MSG + "：" + e.getMessage();
        }
    }

    /**
     * 调用原有API工具（抽取原有callToolApi中的API调用逻辑）
     */
    private String callApiTool(String toolName, Map<String, Object> parameters) {
        if (!TOOL_API_MAP.containsKey(toolName)) {
            throw new IllegalArgumentException("不支持的API工具：" + toolName);
        }
        if (parameters == null || parameters.isEmpty()) {
            throw new IllegalArgumentException(PARAM_ERROR_MSG + "：API工具参数为空");
        }

        String apiPath = TOOL_API_MAP.get(toolName);
        String apiUrl = teamApiBaseUrl + apiPath;
        return callGetApi(apiUrl, parameters, teamAppId, teamAppKey);
    }
    /**
     * 数据查询工具实现（封装原有handleDataQuery逻辑）
     */
    private String executeDataQueryTool(String question, String content) {
        try {
            // 1. 拼接用户问题（含补充信息）
            String userQuestion = question;
            if (content != null && !content.trim().isEmpty()) {
                userQuestion += "\n补充信息：" + content.trim();
            }
            log.info("数据查询工具调用，问题：{}", userQuestion);

            // 2. 路径1：生成SQL → 本地执行
            String finalUserQuestion = userQuestion;
            AtomicReference<String> executeSql = new AtomicReference<>("");
            Callable<String> sqlLocalTask = () -> {
                try {
                    // 调用SQL生成模型获取SQL
                    String sql = llmHttpUtil.call(
                            "SQL生成大模型",
                            sqlBaseUrl,
                            sqlChatId,
                            sqlSessionId,
                            sqlAuth,
                            finalUserQuestion,
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
                    log.info("数据查询工具生成SQL：{}", sql);
                    // 执行SQL并返回结果
                    return mysqlQueryService.executeQuery(sql);
                } catch (Exception e) {
                    log.error("数据查询工具路径1执行失败", e);
                    return "路径1执行失败：" + e.getMessage();
                }
            };

            // 3. 路径2：直接调用数据查询大模型
            Callable<String> dataQueryModelTask = () -> {
                try {
                    return llmHttpUtil.call(
                            "数据查询大模型",
                            dataQueryBaseUrl,
                            dataQueryChatId,
                            dataQuerySessionId,
                            dataQueryAuth,
                            finalUserQuestion,
                            trimmedAnswer -> trimmedAnswer
                    );
                } catch (Exception e) {
                    log.error("数据查询工具路径2执行失败", e);
                    return "路径2执行失败：" + e.getMessage();
                }
            };

            // 4. 并发执行并整合结果
            List<Future<String>> futures = executorService.invokeAll(
                    Arrays.asList(sqlLocalTask, dataQueryModelTask),
                    30, TimeUnit.SECONDS
            );
            String result1 = futures.get(0).get();
            String result2 = futures.get(1).get();
            log.info("数据查询工具双路径结果：result1={}, result2={}", result1, result2);

            // 5. 调用最终整合模型
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
            log.error("数据查询工具执行异常", e);
            return "数据查询工具执行失败：" + e.getMessage();
        }
    }

    /**
     * 知识问答工具实现（封装原有handleKnowledgeQa逻辑）
     */
    private String executeKnowledgeQaTool(String question) {
        try {
            // 第一次调用知识问答大模型
            String firstAnswer = llmHttpUtil.call(
                    "知识问答大模型",
                    knowledgeBaseUrl,
                    knowledgeChatId,
                    knowledgeSessionId,
                    knowledgeAuth,
                    question,
                    trimmedAnswer -> trimmedAnswer
            );

            // 检查结果，必要时二次调用
            if (firstAnswer == null || firstAnswer.trim().isEmpty() || firstAnswer.contains("我没找到答案")
                    || firstAnswer.contains("没找到答案") || firstAnswer.contains("无结果") || firstAnswer.contains("无法回答")) {
                String secondAnswer = llmHttpUtil.call(
                        "知识问答大模型（第二次）",
                        knowledgeBaseUrl,
                        knowledgeChatId,
                        knowledgeSessionId,
                        knowledgeAuth,
                        question,
                        trimmedAnswer -> trimmedAnswer
                );
                return (secondAnswer == null || secondAnswer.trim().isEmpty() || secondAnswer.contains("没找到答案"))
                        ? "我没找到答案" : secondAnswer;
            } else {
                return firstAnswer;
            }
        } catch (Exception e) {
            log.error("知识问答工具调用失败：question={}", question, e);
            return "我没找到答案";
        }
    }
}