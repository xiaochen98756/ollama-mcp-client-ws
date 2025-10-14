package com.client.ws.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

/**
 * @author WuFengSheng
 * @date 2025/4/19 10:14
 */
@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;

    @Getter
    private final ToolCallback[] toolCallbacks;

    public ChatService(OllamaChatModel ollamaChatModel, List<McpSyncClient> mcpSyncClientList) {
        ToolCallbackProvider toolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClientList);
        toolCallbacks = toolCallbackProvider.getToolCallbacks();
        for (ToolCallback toolCallback : toolCallbacks) {
            System.out.println("toolCallback:" + toolCallback.getToolDefinition());
        }
        // chatClient = ChatClient.builder(ollamaChatModel).defaultToolCallbacks(toolCallbacks).build();
        chatClient = ChatClient.builder(ollamaChatModel).defaultToolCallbacks(toolCallbackProvider).build();
    }

    public String askQuestion(Message message) {
        return chatClient.prompt().messages(message).call().content();
    }

    public String askQuestion(SystemMessage systemMessage, UserMessage userMessage) {
        return chatClient.prompt().messages(systemMessage, userMessage).call().content();
    }

}

