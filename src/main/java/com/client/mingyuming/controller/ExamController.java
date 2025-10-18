package com.client.mingyuming.controller;

import com.client.mingyuming.dto.ChatRequest;
import com.client.mingyuming.dto.ChatRequest.Message;
import com.client.mingyuming.dto.ExamRequestDTO;
import com.client.mingyuming.dto.ExamResponseDTO;
import com.client.mingyuming.mcp.ChatService;
import com.client.mingyuming.service.LlmService;
import com.client.mingyuming.service.MysqlQueryService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
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
    private final LlmService llmService;       // 大模型调用服务
    private final ChatService chatService;     // 工具 API 调用服务
    private final MysqlQueryService mysqlQueryService; // MySQL 查询服务

    // 注入两个核心服务
    public ExamController(LlmService llmService, ChatService chatService, MysqlQueryService mysqlQueryService) {
        this.llmService = llmService;
        this.chatService = chatService;
        this.mysqlQueryService = mysqlQueryService;
    }

    /**
     * 比赛核心接口：/api/exam
     * 流程：接收请求 → 调用大模型生成工具指令 → 调用工具 API → 返回结果
     */
    @PostMapping("/exam")
    public ResponseEntity<ExamResponseDTO> handleExamRequest(
            @RequestBody ExamRequestDTO requestDTO) {
        //打印请求信息
        log.info("收到请求：{}", gson.toJson(requestDTO));
        ResponseEntity<ExamResponseDTO> response;
        // 区分请求类型：工具调用题 / 数据查询题
        if (isDataQueryQuestion(requestDTO)) {
            // 数据查询题处理逻辑
            response = handleDataQuery(requestDTO);
        } else {
            // 原有工具调用题处理逻辑（保持不变）
            response = handleToolCall(requestDTO);
        }
        log.info("返回应答：{}", gson.toJson(requestDTO));
        return response;
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
            log.info("试题ID={}，生成的 SQL：{}", requestDTO.getId(), sql);

            // 步骤2：执行 SQL 并获取结果
            String queryResult = mysqlQueryService.executeQuery(sql);
            log.info("试题ID={}，查询结果：{}", requestDTO.getId(), queryResult);

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
     * 判断是否为数据查询题（根据问题内容或分类）TODO
     */
    private boolean isDataQueryQuestion(ExamRequestDTO requestDTO) {
        // 示例：根据问题包含"SQL"、"查询"等关键词判断
        String question = requestDTO.getQuestion().toLowerCase();
        return question.contains("sql") || question.contains("查询") || question.contains("SQL");
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
        systemMsg.setContent("你是银联比赛的工具调用助手，严格遵守以下规则：" +
                "1. 仅输出标准JSON字符串，不包含任何其他内容（无思考过程、无标签）；" +
                "2. JSON必须包含字段：" +
                "   - toolName：工具名称（如credit-card-tool、exchange-rate-tool）；" +
                "   - 对应工具的参数（如信用卡工具需cardNumber、month）；" +
                "3. 信用卡账单工具示例：" +
                "   {\"toolName\":\"credit-card-tool\", \"cardNumber\":\"6211111111111111\", \"month\":\"2025-09\"}" +
                "4. 非工具调用问题，输出：{\"toolName\":\"none\", \"message\":\"非工具调用类问题\"}");
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