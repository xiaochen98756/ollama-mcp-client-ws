package com.client.mingyuming.controller;

import com.client.mingyuming.dto.ChatRequest;
import com.client.mingyuming.dto.ChatRequest.Message;
import com.client.mingyuming.dto.ExamRequestDTO;
import com.client.mingyuming.dto.ExamResponseDTO;
import com.client.mingyuming.mcp.ChatService;
import com.client.mingyuming.service.LlmService;
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
    private final LlmService llmService;       // 大模型调用服务
    private final ChatService chatService;     // 工具 API 调用服务

    // 注入两个核心服务
    public ExamController(LlmService llmService, ChatService chatService) {
        this.llmService = llmService;
        this.chatService = chatService;
    }

    /**
     * 比赛核心接口：/api/exam
     * 流程：接收请求 → 调用大模型生成工具指令 → 调用工具 API → 返回结果
     */
    @PostMapping("/exam")
    public ResponseEntity<ExamResponseDTO> handleExamRequest(
            @RequestBody ExamRequestDTO requestDTO) {
        // 1. 日志打印请求信息
        log.info("收到比赛请求：segments={}, paper={}, 试题ID={}, 问题内容={}",
                requestDTO.getSegments(), requestDTO.getPaper(),
                requestDTO.getId(), requestDTO.getQuestion());

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