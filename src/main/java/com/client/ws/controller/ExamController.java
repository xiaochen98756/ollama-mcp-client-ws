package com.client.ws.controller;

import com.client.ws.dto.ExamRequestDTO;
import com.client.ws.dto.ExamResponseDTO;
import com.client.ws.mcp.ChatService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 比赛专用接口控制器（对外提供 /api/exam 接口）
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/api") // 基础路径
public class ExamController {

    // 复用现有 ChatService，避免重复开发工具调用逻辑
    private final ChatService chatService;

    /**
     * 比赛核心接口：接收工具调用/知识问答等请求，返回标准化响应
     * 接口地址：http://[服务器IP]:10000/api/exam
     * 请求方式：POST
     *  Content-Type：application/json
     */
    @PostMapping("/exam")
    public ResponseEntity<ExamResponseDTO> handleExamRequest(
            @RequestBody ExamRequestDTO requestDTO) {
        // 1. 日志打印请求信息，便于调试
        log.info("收到比赛请求：segments={}, paper={}, 试题ID={}, 问题内容={}",
                requestDTO.getSegments(), requestDTO.getPaper(),
                requestDTO.getId(), requestDTO.getQuestion());

        // 2. 构建系统提示，引导 AI 仅处理工具调用（可根据比赛工具类型扩展）
        SystemMessage systemMsg = new SystemMessage(
                "你是银联比赛的工具调用助手，仅处理以下工具相关请求：" +
                        "1. 汇率换算工具：当问题含「汇率」「换算」「外币」等关键词时调用；" +
                        "2. 手续费计算工具：当问题含「手续费」「收费」「成本」等关键词时调用；" +
                        "3. 若不是工具调用问题，直接返回「非工具调用类问题，无需调用工具」；" +
                        "4. 工具调用结果需简洁，仅返回计算结果（如数字、字符串），无需额外说明。"
        );

        // 3. 构建用户问题（合并 question 和 content，避免遗漏信息）
        String userQuestion = requestDTO.getQuestion() +
                (requestDTO.getContent() != null ? "\n补充信息：" + requestDTO.getContent() : "");
        UserMessage userMsg = new UserMessage(userQuestion);

        // 4. 调用 ChatService 触发工具调用（复用现有 MCP 客户端逻辑）
        String toolCallResult = chatService.askQuestion(systemMsg, userMsg);
        log.info("试题ID={} 的工具调用结果：{}", requestDTO.getId(), toolCallResult);

        // 5. 封装响应（严格匹配比赛格式要求）
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments()); // 与请求一致
        responseDTO.setPaper(requestDTO.getPaper());       // 与请求一致
        responseDTO.setId(requestDTO.getId());             // 与请求一致
        responseDTO.setAnswer(toolCallResult);             // 工具调用结果/答案

        // 6. 返回 200 OK 响应
        return ResponseEntity.ok(responseDTO);
    }
}