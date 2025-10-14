package com.client.ws.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.client.ws.core.SpringWebSocketConfigurator;
import com.client.ws.mcp.ChatService;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * @author WuFengSheng
 * @date 2025/4/20 07:07
 */
@Slf4j
@Component
@AllArgsConstructor
// WebSocket 访问路径：ws://localhost:9802/mcp/ws
@ServerEndpoint(value = "/ws", configurator = SpringWebSocketConfigurator.class)
public class WsServerEndpoint {

    private final ChatService chatService;

    @OnOpen
    public void onOpen(Session session) {
        log.info("连接建立：sessionId={}", session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        log.info("收到消息：sessionId={}, message={}", session.getId(), message);
        if ("ping".equals(message)) {
            log.info("sessionId：{} ping", session.getId());
            sendMessage(session, "pong");
            return;
        }
        JSONObject json = JSON.parseObject(message);
        String action = json.getString("action");
        JSONObject payload = json.getJSONObject("payload");
        switch (action) {
            case "listTools":
                ToolCallback[] toolCallbacks = chatService.getToolCallbacks();
                sendMessage(session, JSON.toJSONString(toolCallbacks));
                break;
            case "chat":
                String prompt = payload.getString("prompt");
                String result = chatService.askQuestion(
                        new SystemMessage("你是一个工具调用助手。"),
                        new UserMessage(prompt));
                log.info("处理结果：{}", result);
                sendMessage(session, result);
                break;
        }
    }

    @OnClose
    public void onClose(Session session) {
        log.info("连接关闭：sessionId={}", session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.info("异常：sessionId={}, error={}", session.getId(), error.getMessage());
    }

    private void sendMessage(Session session, String message) {
        try {
            session.getBasicRemote().sendText(message);
        } catch (Exception e) {
            System.out.println("发送消息失败: " + e.getMessage());
        }
    }

}
