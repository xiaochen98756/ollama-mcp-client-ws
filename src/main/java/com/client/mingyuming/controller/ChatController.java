package com.client.mingyuming.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.client.mingyuming.mcp.ChatService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP 接口控制器，替代原 WebSocket 服务
 * 访问路径：http://localhost:9802/mcp/api/chat/...
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/api/chat") // 基础路径，与原 WebSocket 功能对应
public class ChatController {

    private final ChatService chatService;

    /**
     * 心跳检测接口（替代 WebSocket 的 ping-pong）
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        log.info("收到 ping 请求");
        return ResponseEntity.ok("pong");
    }

    /**
     * 查询工具列表（对应原 "listTools" 动作）
     */
    @GetMapping("/tools")
    public ResponseEntity<String> listTools() {
        log.info("查询工具列表");
        ToolCallback[] toolCallbacks = chatService.getToolCallbacks();
        return ResponseEntity.ok(JSON.toJSONString(toolCallbacks));
    }

    /**
     * 对话接口（对应原 "chat" 动作）
     * 请求体格式：{"prompt": "你的问题"}
     */
    @PostMapping("/query")
    public ResponseEntity<String> chat(@RequestBody JSONObject payload) {
        String prompt = payload.getString("prompt");
        log.info("收到对话请求：prompt={}", prompt);

        // 调用原有 ChatService 逻辑，保持业务一致性
        String result = chatService.askQuestion(
                new SystemMessage("你是一个工具调用助手。"),
                new UserMessage(prompt)
        );

        log.info("对话结果：{}", result);
        return ResponseEntity.ok(result);
    }
}