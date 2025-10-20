package com.client.mingyuming.controller;

import com.client.mingyuming.dto.ChatRequest;
import com.client.mingyuming.dto.ChatRequest.Message;
import com.client.mingyuming.dto.ExamRequestDTO;
import com.client.mingyuming.dto.ExamResponseDTO;
import com.client.mingyuming.service.ChatService;
import com.client.mingyuming.service.LLMService;
import com.client.mingyuming.service.MysqlQueryService;
import com.client.mingyuming.util.LlmHttpUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 比赛专用接口控制器（核心入口）
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ExamController {

    // ------------------------------
    // 1. 注入各模型配置参数（从 application.yml 读取）
    // ------------------------------
    // 分类模型参数
    @Value("${llm.classify.base-url}")
    private String classifyBaseUrl;
    @Value("${llm.classify.chat-id}")
    private String classifyChatId;
    @Value("${llm.classify.session-id}")
    private String classifySessionId;
    @Value("${llm.classify.authorization}")
    private String classifyAuth;


    // SQL 生成模型参数
    @Value("${llm.sql-generate.base-url}")
    private String sqlBaseUrl;
    @Value("${llm.sql-generate.chat-id}")
    private String sqlChatId;
    @Value("${llm.sql-generate.session-id}")
    private String sqlSessionId;
    @Value("${llm.sql-generate.authorization}")
    private String sqlAuth;

    // 数据查询大模型参数
    @Value("${llm.data-query.base-url}")
    private String dataQueryBaseUrl;
    @Value("${llm.data-query.chat-id}")
    private String dataQueryChatId;
    @Value("${llm.data-query.session-id}")
    private String dataQuerySessionId;
    @Value("${llm.data-query.authorization}")
    private String dataQueryAuth;

    // 结果整合大模型参数
    @Value("${llm.final-result.base-url}")
    private String finalResultBaseUrl;
    @Value("${llm.final-result.chat-id}")
    private String finalResultChatId;
    @Value("${llm.final-result.session-id}")
    private String finalResultSessionId;
    @Value("${llm.final-result.authorization}")
    private String finalResultAuth;

    // 知识问答大模型参数
    @Value("${llm.knowledge-chat.base-url}")
    private String knowledgeBaseUrl;
    @Value("${llm.knowledge-chat.chat-id}")
    private String knowledgeChatId;
    @Value("${llm.knowledge-chat.session-id}")
    private String knowledgeSessionId;
    @Value("${llm.knowledge-chat.authorization}")
    private String knowledgeAuth;

    // 注入工具结果处理提示词
    @Value("${llm.tool-result-prompt}")
    private String toolResultPrompt;
    // ------------------------------
    // 2. 注入服务和工具
    // ------------------------------
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private final LLMService llmService;       // 原有大模型服务（知识问答用）
    private final ChatService chatService;     // 工具 API 调用服务
    private final MysqlQueryService mysqlQueryService; // MySQL 查询服务
    private final ExecutorService executorService; // 线程池，用于并发执行任务

    @Autowired
    LlmHttpUtil llmHttpUtil;

    @Value("${llm.system.prompt}")
    private String systemPrompt;

    // 构造方法注入
    public ExamController(LLMService llmService,
                          ChatService chatService,
                          MysqlQueryService mysqlQueryService) {
        this.llmService = llmService;
        this.chatService = chatService;
        this.mysqlQueryService = mysqlQueryService;
        this.executorService = Executors.newFixedThreadPool(2); // 初始化线程池
    }

    /**
     * 比赛核心接口：/api/exam（增强版，支持HARD难度的动态工具选择）
     */
    @PostMapping("/exam")
    public ResponseEntity<ExamResponseDTO> handleExamRequest(
            @RequestBody ExamRequestDTO requestDTO) {
        // 1. 基础初始化
        log.info("收到请求：{}", gson.toJson(requestDTO));
        String originalQuestion = requestDTO.getQuestion();
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());

        try {
            // 2. 判断试卷难度是否为HARD（TEST_HARD或EXAM_HARD）
            String paperType = requestDTO.getPaper();
            if ("TEST_HARD".equals(paperType) || "EXAM_HARD".equals(paperType)) {
                // 2.1 构建工具选择的提示词（告知大模型可用工具）
                String toolSelectionPrompt = buildToolSelectionPrompt(originalQuestion, requestDTO.getContent());
                // 2.2 调用大模型决策工具类型
                String toolDecisionJson = llmService.generateToolDecision(toolSelectionPrompt);
                log.info("HARD难度，大模型决策结果：{}", toolDecisionJson);
                // 2.3 解析决策结果并调用对应工具
                String toolResult = chatService.executeToolByDecision(toolDecisionJson, originalQuestion, requestDTO.getContent());
                responseDTO.setAnswer(toolResult);
            } else {
                // 非HARD难度：沿用原有分类路由逻辑
                String requestType = llmHttpUtil.call(
                        "意图识别大模型",
                        classifyBaseUrl,
                        classifyChatId,
                        classifySessionId,
                        classifyAuth,
                        originalQuestion,
                        trimmedAnswer -> {
                            Map<String, String> answerJson = gson.fromJson(trimmedAnswer, new TypeToken<Map<String, String>>() {}.getType());
                            String type = answerJson.getOrDefault("requestType", "").trim();
                            return ("knowledge_qa".equals(type) || "tool_call".equals(type) || "data_query".equals(type))
                                    ? type : "knowledge_qa";
                        }
                );
                // 按原有逻辑路由
                switch (requestType) {
                    case "data_query" -> responseDTO = handleDataQuery(requestDTO).getBody();
                    case "tool_call" -> responseDTO = handleToolCall(requestDTO).getBody();
                    case "knowledge_qa" -> responseDTO.setAnswer(handleKnowledgeQa(originalQuestion));
                    default -> responseDTO.setAnswer(handleKnowledgeQa(originalQuestion));
                }
            }

            log.info("返回应答：{}", gson.toJson(responseDTO));
            return ResponseEntity.ok(responseDTO);

        } catch (Exception e) {
            log.error("请求处理异常", e);
            responseDTO.setAnswer("系统繁忙，请稍后重试：" + e.getMessage());
            return ResponseEntity.ok(responseDTO);
        }
    }
    /**
     * 处理知识问答：直接返回结果，无结果时返回固定字符串
     */
    private String handleKnowledgeQa(String question) {
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

            // 检查第一次结果是否为空、无意义或包含无结果相关表述
            if (firstAnswer == null || firstAnswer.trim().isEmpty() || firstAnswer.contains("我没找到答案")
                    || firstAnswer.contains("没找到答案") || firstAnswer.contains("无结果") || firstAnswer.contains("无法回答")) {
                // 进行第二次调用（兜底查询）
                String secondAnswer = llmHttpUtil.call(
                        "知识问答大模型（第二次）",
                        knowledgeBaseUrl,
                        knowledgeChatId,
                        knowledgeSessionId,
                        knowledgeAuth,
                        question,
                        trimmedAnswer -> trimmedAnswer
                );

                // 检查第二次结果
                if (secondAnswer == null || secondAnswer.trim().isEmpty() || secondAnswer.contains("我没找到答案")
                        || secondAnswer.contains("没找到答案") || secondAnswer.contains("无结果") || secondAnswer.contains("无法回答")) {
                    return "我没找到答案";
                } else {
                    return secondAnswer;
                }
            } else {
                return firstAnswer;
            }
        } catch (Exception e) {
            log.error("知识问答大模型调用失败：question={}", question, e);
            return "我没找到答案";
        }
    }

    /**
     * 处理数据查询：双路径并发执行 + 结果归集 + 最终生成
     */
    private ResponseEntity<ExamResponseDTO> handleDataQuery(ExamRequestDTO requestDTO) {
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());

        try {
            // 1. 拼接用户问题（含补充信息）
            String userQuestion = requestDTO.getQuestion();
            if (requestDTO.getContent() != null && !requestDTO.getContent().trim().isEmpty()) {
                userQuestion += "\n补充信息：" + requestDTO.getContent().trim();
            }
            log.info("试题ID={}，数据查询问题：{}", requestDTO.getId(), userQuestion);

            // 2. 路径1：生成SQL → 本地执行
            String finalUserQuestion = userQuestion;
            AtomicReference<String> excuteSql= new AtomicReference<>("");
            Callable<String> sqlLocalTask = () -> {
                try {
                    // 2.1 调用SQL生成模型获取SQL
                    String sql = llmHttpUtil.call(
                            "SQL生成大模型",
                            sqlBaseUrl,
                            sqlChatId,
                            sqlSessionId,
                            sqlAuth,
                            finalUserQuestion,
                            // SQL处理器：提取纯SQL
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
                    excuteSql.set(sql);
                    log.info("试题ID={}，路径1（生成SQL → 本地执行）生成SQL：{}", requestDTO.getId(), sql);

                    // 2.2 本地执行SQL并返回结果
                    return mysqlQueryService.executeQuery(sql);
                } catch (Exception e) {
                    log.error("试题ID={}，路径1执行失败", requestDTO.getId(), e);
                    return "路径1执行失败：" + e.getMessage();
                }
            };

            // 3. 路径2：直接调用数据查询大模型获取结果
            String finalUserQuestion1 = userQuestion;
            Callable<String> dataQueryModelTask = () -> {
                try {
                    return llmHttpUtil.call(
                            "数据查询大模型",
                            dataQueryBaseUrl,
                            dataQueryChatId,
                            dataQuerySessionId,
                            dataQueryAuth,
                            finalUserQuestion1,
                            // 数据查询模型处理器：直接返回结果
                            trimmedAnswer -> trimmedAnswer
                    );
                } catch (Exception e) {
                    log.error("试题ID={}，路径2执行失败", requestDTO.getId(), e);
                    return "路径2执行失败：" + e.getMessage();
                }
            };

            // 4. 并发执行两个任务，等待结果（超时控制：30秒）
            List<Future<String>> futures = executorService.invokeAll(
                    Arrays.asList(sqlLocalTask, dataQueryModelTask),
                    30, TimeUnit.SECONDS
            );
            String result1 = futures.get(0).get(); // 路径1结果（本地SQL执行结果）
            String result2 = futures.get(1).get(); // 路径2结果（数据查询模型结果）
            log.info("试题ID={}，双路径结果归集：result1（生成SQL → 本地执行）={}, result2（直接调用数据查询大模型获取结果）={}",
                    requestDTO.getId(), result1, result2);

            // 5. 调用第三个大模型：整合两个结果生成最终答案
            String finalQuestion = String.format(
                    "用户问题：%s\n结果1：%s,执行sql：%s\n结果2：%s",
                    userQuestion, result1, excuteSql.get(),result2
            );
            String finalAnswer = llmHttpUtil.call(
                    "数据比对拼接大模型",
                    finalResultBaseUrl,
                    finalResultChatId,
                    finalResultSessionId,
                    finalResultAuth,
                    finalQuestion,
                    // 最终结果处理器：直接返回整合后的文本
                    trimmedAnswer -> trimmedAnswer
            );
            // 处理无结果场景
            if (result1.contains("路径1执行失败") && result2.contains("路径2执行失败")) {
                responseDTO.setAnswer("无结果");
            } else if ("00".equals(finalAnswer.trim())) {
                responseDTO.setAnswer(result2);
                log.info("试题ID={}，最终整合结果：{}", requestDTO.getId(), result2);
            } else {
                responseDTO.setAnswer(finalAnswer);
                log.info("试题ID={}，最终整合结果：{}", requestDTO.getId(), finalAnswer);
            }
        } catch (Exception e) {
            log.error("试题ID={}，数据查询整体处理失败", requestDTO.getId(), e);
            responseDTO.setAnswer("无结果");
        }

        return ResponseEntity.ok(responseDTO);
    }
    /**
     * 构建工具选择的提示词（告知大模型可用工具及参数要求）
     */
    private String buildToolSelectionPrompt(String question, String content) {
        return String.format("""
                请根据用户问题选择合适的工具执行，可用工具如下：
                1. %s：处理需要查询数据库的问题，参数为用户问题和补充信息
                2. %s：处理常识性、知识性问题，参数为用户问题
                3. credit-card-tool：查询信用卡账单，参数需包含卡号、月份
                4. exchange-rate-tool：查询汇率，参数需包含源货币、目标货币、金额
                5. utility-bill-tool：查询水电煤账单，参数需包含户号、类型、月份
                6. user-asset-tool：查询用户资产，参数需包含用户ID
                7. payment-order-tool：创建支付订单，参数需包含金额、商户号
                8. %s：获取当前日期，无参数
                9. %s：计算数学表达式，参数为表达式字符串
                
                用户问题：%s
                补充信息：%s
                
                请返回JSON格式：{"toolName":"工具名","parameters":{"参数名":"参数值"}}，若无需工具直接返回{"toolName":"none","message":"回答内容"}
                """,
                ChatService.DATA_QUERY_TOOL,
                ChatService.KNOWLEDGE_QA_TOOL,
                ChatService.CURRENT_DATE_TOOL,
                ChatService.CALCULATOR_TOOL,
                question,
                (content == null ? "无" : content)
        );
    }
    /**
     * 工具调用逻辑
     */
    private ResponseEntity<ExamResponseDTO> handleToolCall(ExamRequestDTO requestDTO) {
        ChatRequest chatRequest = new ChatRequest();
        List<Message> messages = new ArrayList<>();

        // 1. 原有逻辑：调用工具生成指令并执行
        // 系统提示（工具调用规则）
        Message systemMsg = new Message();
        systemMsg.setRole("system");
        systemMsg.setContent(systemPrompt);
        messages.add(systemMsg);

        // 用户问题（合并 question 和 content）
        Message userMsg = new Message();
        String userQuestion = requestDTO.getQuestion() +
                (requestDTO.getContent() != null ? "\n补充信息：" + requestDTO.getContent() : "");
        userMsg.setRole("user");
        userMsg.setContent(userQuestion);
        messages.add(userMsg);

        chatRequest.setMessages(messages);

        // 调用大模型获取调用哪个工具
        String toolCmd = llmService.generateResponse(chatRequest);
        log.info("试题ID={}，工具指令：{}", requestDTO.getId(), toolCmd);

        // 调用工具 API 获取原始结果
        String toolResult = chatService.callToolApi(toolCmd);
        log.info("试题ID={}，工具原始结果：{}", requestDTO.getId(), toolResult);


        // 2. 新增：调用大模型处理工具结果（按题目类型格式化）
        // 2.1 获取题目类型（从requestDTO获取，如"选择题"或"问答题"）
        String questionType = requestDTO.getCategory();  // 假设DTO中已有category字段存储题目类型
        log.info("试题ID={}，题目类型：{}", requestDTO.getId(), questionType);

        // 2.2 构建结果处理的提示词（替换占位符）
        String formattedPrompt = String.format(toolResultPrompt, questionType, toolResult);

        // 2.3 构建新的ChatRequest，用于结果处理
        ChatRequest resultProcessRequest = new ChatRequest();
        List<Message> resultMessages = new ArrayList<>();
        // 系统提示：指定结果处理规则
        Message resultSystemMsg = new Message();
        resultSystemMsg.setRole("system");
        resultSystemMsg.setContent(formattedPrompt);
        resultMessages.add(resultSystemMsg);
        // 用户消息：触发处理（可空，或重复问题便于模型理解）
        Message resultUserMsg = new Message();
        resultUserMsg.setRole("user");
        resultUserMsg.setContent("请根据上述规则处理结果并返回最终答案");
        resultMessages.add(resultUserMsg);
        resultProcessRequest.setMessages(resultMessages);

        // 2.4 调用大模型生成最终答案（复用现有方法）
        String finalAnswer = llmService.generateResponse(resultProcessRequest);
        log.info("试题ID={}，格式化后最终答案：{}", requestDTO.getId(), finalAnswer);


        // 3. 封装响应
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());
        responseDTO.setAnswer(finalAnswer);

        return ResponseEntity.ok(responseDTO);
    }
}