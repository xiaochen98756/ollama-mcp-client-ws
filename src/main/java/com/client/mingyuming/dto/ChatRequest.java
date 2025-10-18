package com.client.mingyuming.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    // 对话消息列表（包含多轮历史消息）
    private List<Message> messages;

    // 内部类：单条消息的结构
    @Data
    public static class Message {
        private String role;   // 角色：user（用户）、assistant（助手）、system（系统）
        private String content; // 消息内容（如用户的问题、助手的回答）
    }
}