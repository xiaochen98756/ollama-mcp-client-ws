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
     * 比赛核心接口：/api/exam
     * 流程：接收请求 → 通用工具类调用分类模型 → 按类型路由 → 返回结果
     */
    @PostMapping("/exam")
    public ResponseEntity<ExamResponseDTO> handleExamRequest(
            @RequestBody ExamRequestDTO requestDTO) {
        // 1. 打印请求信息
        log.info("收到请求：{}", gson.toJson(requestDTO));
        String originalQuestion = requestDTO.getQuestion();
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        // 初始化响应公共字段
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());

        try {
            // 2. 核心：通用工具类调用分类模型（差异化处理器：解析 JSON 中的 requestType）
            String requestType = llmHttpUtil.call(
                    classifyBaseUrl,
                    classifyChatId,
                    classifySessionId,
                    classifyAuth,
                    originalQuestion,
                    // 分类模型 answer 处理器：解析 JSON 提取 requestType
                    trimmedAnswer -> {
                        Map<String, String> answerJson = gson.fromJson(
                                trimmedAnswer, new TypeToken<Map<String, String>>() {}.getType()
                        );
                        String type = answerJson.getOrDefault("requestType", "").trim();
                        // 非法类型降级为知识问答
                        return ("knowledge_qa".equals(type) || "tool_call".equals(type) || "data_query".equals(type))
                                ? type : "knowledge_qa";
                    }
            );

            // 3. 按分类结果路由
            switch (requestType) {
                case "data_query" ->
                        responseDTO = handleDataQuery(requestDTO).getBody();
                case "tool_call" ->
                        responseDTO = handleToolCall(requestDTO).getBody();
                case "knowledge_qa" ->
                        responseDTO.setAnswer(handleKnowledgeQa(originalQuestion));
                default -> {
                    log.warn("未知请求类型：{}，降级为知识问答", requestType);
                    responseDTO.setAnswer(handleKnowledgeQa(originalQuestion));
                }
            }

            // 4. 返回响应
            log.info("返回应答：{}", gson.toJson(responseDTO));
            return ResponseEntity.ok(responseDTO);

        } catch (Exception e) {
            // 全局异常处理
            log.error("请求处理异常", e);
            responseDTO.setAnswer("系统繁忙，请稍后重试：" + e.getMessage());
            return ResponseEntity.ok(responseDTO);
        }
    }

    /**
     * 处理知识问答：复用原有 LLMService
     */
    private String handleKnowledgeQa(String question) {
        ChatRequest chatRequest = new ChatRequest();
        List<Message> messages = new ArrayList<>();

        // 系统提示
        Message systemMsg = new Message();
        systemMsg.setRole("system");
        systemMsg.setContent("你是知识问答助手，直接回答用户问题，无需调用工具，仅返回纯文本答案。");
        messages.add(systemMsg);

        // 用户问题
        Message userMsg = new Message();
        userMsg.setRole("user");
        userMsg.setContent(question);
        messages.add(userMsg);

        chatRequest.setMessages(messages);
        log.info("处理知识问答：question={}", question);
        return llmService.generateResponse(chatRequest);
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
                    finalResultBaseUrl,
                    finalResultChatId,
                    finalResultSessionId,
                    finalResultAuth,
                    finalQuestion,
                    // 最终结果处理器：直接返回整合后的文本
                    trimmedAnswer -> trimmedAnswer
            );
            log.info("试题ID={}，最终整合结果：{}", requestDTO.getId(), finalAnswer);

            // 6. 封装最终结果
            responseDTO.setAnswer(finalAnswer);

        } catch (Exception e) {
            log.error("试题ID={}，数据查询整体处理失败", requestDTO.getId(), e);
            responseDTO.setAnswer("数据查询处理失败：" + e.getMessage());
        }

        return ResponseEntity.ok(responseDTO);
    }

    /**
     * 原有工具调用逻辑（保持不变）
     */
    private ResponseEntity<ExamResponseDTO> handleToolCall(ExamRequestDTO requestDTO) {
        ChatRequest chatRequest = new ChatRequest();
        List<Message> messages = new ArrayList<>();

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

        // 调用大模型生成工具指令
        String toolCmd = llmService.generateResponse(chatRequest);
        log.info("试题ID={}，工具指令：{}", requestDTO.getId(), toolCmd);

        // 调用工具 API
        String toolResult = chatService.callToolApi(toolCmd);
        log.info("试题ID={}，工具结果：{}", requestDTO.getId(), toolResult);

        // 封装响应
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());
        responseDTO.setAnswer(toolResult);

        return ResponseEntity.ok(responseDTO);
    }
}