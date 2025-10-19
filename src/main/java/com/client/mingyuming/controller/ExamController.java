package com.client.mingyuming.controller;

import com.client.mingyuming.client.ClassifyLlmClient;
import com.client.mingyuming.dto.ChatRequest;
import com.client.mingyuming.dto.ChatRequest.Message;
import com.client.mingyuming.dto.ExamRequestDTO;
import com.client.mingyuming.dto.ExamResponseDTO;
import com.client.mingyuming.service.ChatService;
import com.client.mingyuming.service.LLMService;
import com.client.mingyuming.service.MysqlQueryService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 比赛专用接口控制器（核心入口）
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ExamController {
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()  // 关键：启用格式化输出
            .create();
    private final LLMService llmService;       // 大模型调用服务
    private final ChatService chatService;     // 工具 API 调用服务
    private final MysqlQueryService mysqlQueryService; // MySQL 查询服务
    private final ClassifyLlmClient classifyLlmClient; //大模型分类服务
    @Value("${llm.system.prompt}")
    private String systemPrompt;
    // 注入两个核心服务
    public ExamController(LLMService llmService,
                          ChatService chatService,
                          MysqlQueryService mysqlQueryService,
                          ClassifyLlmClient classifyLlmClient) {
        this.llmService = llmService;
        this.chatService = chatService;
        this.mysqlQueryService = mysqlQueryService;
        this.classifyLlmClient = classifyLlmClient;
    }

    /**
     * 比赛核心接口：/api/exam
     * 流程：接收请求 → 调用大模型生成工具指令 → 调用工具 API → 返回结果
     */
    @PostMapping("/exam")
    public ResponseEntity<ExamResponseDTO> handleExamRequest(
            @RequestBody ExamRequestDTO requestDTO) {
        // 1. 打印请求信息
        log.info("收到请求：{}", gson.toJson(requestDTO));
        String originalQuestion = requestDTO.getQuestion();
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        // 初始化响应公共字段（无论哪种类型都需要）
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());

        try {
            // 2. 核心：调用分类大模型，判断请求类型
            String requestType = classifyLlmClient.getRequestType(originalQuestion);

            // 3. 按分类结果路由到不同处理逻辑
            switch (requestType) {
                case "data_query" ->
                        // 数据查询题：原有 handleDataQuery 逻辑
                        responseDTO = handleDataQuery(requestDTO).getBody();
                case "tool_call" ->
                        // 工具调用题：原有 handleToolCall 逻辑
                        responseDTO = handleToolCall(requestDTO).getBody();
                case "knowledge_qa" ->
                        // 知识问答题：新增逻辑（直接调用大模型返回答案，无需工具/数据库）
                        responseDTO.setAnswer(handleKnowledgeQa(originalQuestion));
                default -> {
                    // 未知类型：降级为知识问答
                    log.warn("分类大模型返回未知类型：{}，降级为知识问答", requestType);
                    responseDTO.setAnswer(handleKnowledgeQa(originalQuestion));
                }
            }

            // 4. 打印并返回响应
            log.info("返回应答：{}", gson.toJson(responseDTO));
            return ResponseEntity.ok(responseDTO);

        } catch (Exception e) {
            // 全局异常处理：避免接口报错
            log.error("请求处理异常", e);
            responseDTO.setAnswer("系统繁忙，请稍后重试：" + e.getMessage());
            return ResponseEntity.ok(responseDTO);
        }
    }
    /**
     * 新增：处理知识问答题（直接调用大模型返回答案）
     */
    private String handleKnowledgeQa(String question) {
        // 构建大模型请求（仅需系统提示+用户问题）
        ChatRequest chatRequest = new ChatRequest();
        List<Message> messages = new ArrayList<>();

        // 系统提示：明确知识问答无需调用工具
        Message systemMsg = new Message();
        systemMsg.setRole("system");
        systemMsg.setContent("你是知识问答助手，直接回答用户问题，无需调用任何工具，仅返回答案文本，不包含JSON或其他格式。");
        messages.add(systemMsg);

        // 用户问题
        Message userMsg = new Message();
        userMsg.setRole("user");
        userMsg.setContent(question);
        messages.add(userMsg);

        chatRequest.setMessages(messages);

        // 调用大模型获取答案（复用原有 LLMService）
        log.info("处理知识问答：question={}", question);
        return llmService.generateResponse(chatRequest);
    }
    /**
     * 处理数据查询题：生成 SQL → 执行查询 → 返回结果
     */
    private ResponseEntity<ExamResponseDTO> handleDataQuery(ExamRequestDTO requestDTO) {
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());

        try {
            // 步骤1：调用大模型生成 SQL（预留逻辑，暂时用示例 SQL 代替）
            String sql = generateSqlByLlm(requestDTO);
            log.info("试题ID={}，调用大模型生成的 SQL：{}", requestDTO.getId(), sql);

            // 步骤2：执行 SQL 并获取结果
            String queryResult = mysqlQueryService.executeQuery(sql);
            log.info("试题ID={}，查询数据库得到的结果：{}", requestDTO.getId(), queryResult);

            // 步骤3：封装结果
            responseDTO.setAnswer(queryResult);

        } catch (Exception e) {
            log.error("数据查询处理失败", e);
            responseDTO.setAnswer("处理失败：" + e.getMessage());
        }

        return ResponseEntity.ok(responseDTO);
    }

    /**
     * 调用大模型生成 SQL（预留方法，后续对接 LLM）
     * 目前返回示例 SQL（匹配用户提供的示例问题）
     */
    private String generateSqlByLlm(ExamRequestDTO requestDTO) {
        // 示例：用户问题是查询商户类型为'ONLINE'的前5个商户信息
        // 实际场景中，这里应该调用 llmService 生成 SQL
        return "SELECT merchant_id, merchant_name, business_license " +
                "FROM merchant_info " +
                "WHERE merchant_type = 'ONLINE' " +
                "LIMIT 5";
    }

    /**
     * 原有工具调用题处理逻辑（保持不变）
     */
    private ResponseEntity<ExamResponseDTO> handleToolCall(ExamRequestDTO requestDTO) {
        // 2. 构建大模型请求（系统提示 + 用户问题）
        ChatRequest chatRequest = new ChatRequest();
        List<Message> messages = new ArrayList<>();

        // 2.1 系统提示（工具调用规则）
        Message systemMsg = new Message();
        systemMsg.setRole("system");
        // 原系统提示词基础上，增加更严格的格式约束
        systemMsg.setContent(systemPrompt);
        messages.add(systemMsg);

        // 2.2 用户问题（合并 question 和 content）
        Message userMsg = new Message();
        String userQuestion = requestDTO.getQuestion() +
                (requestDTO.getContent() != null ? "\n补充信息：" + requestDTO.getContent() : "");
        userMsg.setRole("user");
        userMsg.setContent(userQuestion);
        messages.add(userMsg);

        chatRequest.setMessages(messages);

        // 3. 调用大模型生成工具指令
        String toolCmd = llmService.generateResponse(chatRequest);
        log.info("试题ID={}，大模型生成工具指令：{}", requestDTO.getId(), toolCmd);

        // 4. 调用工具 API 执行指令
        String toolResult = chatService.callToolApi(toolCmd);
        log.info("试题ID={}，工具调用结果：{}", requestDTO.getId(), toolResult);

        // 5. 封装响应并返回
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());
        responseDTO.setAnswer(toolResult);

        return ResponseEntity.ok(responseDTO);
    }
}