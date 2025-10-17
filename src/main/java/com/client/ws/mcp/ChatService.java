package com.client.ws.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 适配比赛场景的 MCP 工具调用服务
 * 基于可编译版本增强：异常处理、日志调试、标准化响应、工具加载校验
 * @author WuFengSheng
 * @date 2025/4/19 10:14
 */
@Slf4j
@Service
public class ChatService {

    // 大模型客户端（已绑定 MCP 工具，支持自动调用）
    private final ChatClient chatClient;

    // 加载的 MCP 工具列表（对外暴露工具信息，便于健康检查）
    @Getter
    private final ToolCallback[] toolCallbacks;

    // 比赛场景标准化提示与错误响应（避免返回非预期内容）
    // 1. 系统提示：引导模型正确调用工具，匹配比赛工具类型
    private static final String DEFAULT_SYSTEM_PROMPT = "你是银联创新大赛的工具调用助手，仅处理以下任务：" +
            "1. 汇率换算：问题含「汇率」「换算」「外币」时，调用汇率工具，参数需含「金额」「源币种」「目标币种」；" +
            "2. 手续费计算：问题含「手续费」「收费」「成本」时，调用手续费工具，参数需含「交易金额」「商户类型」；" +
            "3. 非工具调用问题（如知识问答、闲聊），直接返回「非工具调用类问题」，无需额外内容；" +
            "4. 工具调用结果仅返回核心数据（如数字、字符串），不要多余解释，格式严格匹配比赛要求。";

    // 2. 标准化错误提示（统一响应格式，便于评分）
    private static final String TOOL_TIMEOUT_MSG = "工具调用超时，请重试";
    private static final String TOOL_ERROR_MSG = "工具调用失败，请检查参数";
    private static final String TOOL_NOT_FOUND_MSG = "未找到匹配的工具";
    private static final String SYSTEM_ERROR_MSG = "系统繁忙，请稍后再试";

    /**
     * 构造方法：初始化 MCP 工具 + ChatClient（保留原有可编译逻辑，增强校验）
     * @param ollamaChatModel Ollama 模型实例（Spring 容器注入）
     * @param mcpSyncClientList MCP 同步客户端列表（从配置文件加载）
     */
    public ChatService(OllamaChatModel ollamaChatModel, List<McpSyncClient> mcpSyncClientList) {
        // 1. 初始化 MCP 工具回调器（保留原有逻辑，补充非空校验）
        ToolCallbackProvider toolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClientList);
        // 增强：避免工具列表为空时强转异常，默认返回空数组
        this.toolCallbacks = Objects.nonNull(toolCallbackProvider.getToolCallbacks())
                ? (ToolCallback[]) toolCallbackProvider.getToolCallbacks()
                : new ToolCallback[0];

        // 2. 工具加载日志（增强调试：打印工具名称+描述，便于确认是否加载目标工具）
        log.info("MCP 工具加载完成，共加载 {} 个工具", toolCallbacks.length);
        for (int i = 0; i < toolCallbacks.length; i++) {
            ToolCallback tool = toolCallbacks[i];
            log.info("工具[{}]：名称={}，描述={}",
                    i + 1,
                    tool.getToolDefinition().name(),
                    tool.getToolDefinition().description());
        }

        // 3. 初始化 ChatClient（保留原有 defaultTools 绑定方式，补充模型参数优化）
        this.chatClient = ChatClient.builder(ollamaChatModel)
                .defaultTools(toolCallbacks) // 原有可编译的工具绑定方式，不修改
                .build();

        // 4. 工具加载警告（增强：若未加载工具，提示配置问题，避免比赛时踩坑）
        if (toolCallbacks.length == 0) {
            log.warn("未加载到任何 MCP 工具！请检查 application.yml 中 MCP 客户端配置（如工具配置文件路径、MCP Server 地址）");
        }
    }

    /**
     * 重载1：处理单条消息（简单场景，自动附加默认系统提示）
     * 适用：无需自定义系统提示的场景，直接使用比赛默认工具调用规则
     * @param message 输入消息（用户问题）
     * @return 工具调用结果 / 标准化响应
     */
    public String askQuestion(Message message) {
        // 自动拼接默认系统提示，避免用户消息无工具调用引导
        SystemMessage defaultSystemMsg = new SystemMessage(DEFAULT_SYSTEM_PROMPT);
        return askQuestion(defaultSystemMsg, new UserMessage(message.toString()));
    }

    /**
     * 重载2：处理带自定义系统提示的消息（灵活场景，支持自定义工具规则）
     * 适用：需要特殊工具调用规则的场景（如比赛决赛的复杂工具调用）
     * @param systemMessage 自定义系统提示（如特定工具参数格式要求）
     * @param userMessage 用户问题（比赛题目）
     * @return 工具调用结果 / 标准化响应
     */
    public String askQuestion(SystemMessage systemMessage, UserMessage userMessage) {
        try {

            // 核心：调用模型+工具（保留原有逻辑，补充结果非空校验）
            String result = chatClient.prompt()
                    .messages(systemMessage, userMessage)
                    .call()
                    .content();

            // 增强：打印响应日志，便于追溯工具调用结果
            log.debug("工具调用响应：用户问题={}，结果={}",
                    userMessage.toString(),
                    result.length() > 50 ? result.substring(0, 50) + "..." : result);

            // 增强：避免返回空结果，默认返回系统错误
            return Objects.nonNull(result) && !result.isEmpty() ? result : SYSTEM_ERROR_MSG;

        } catch (ToolExecutionException e) {
            // 捕获工具执行异常（区分超时/其他错误，返回标准化提示）
            log.error("工具执行异常（用户问题：{}）", userMessage.toString(), e);
            return e.getMessage().toLowerCase().contains("timeout")
                    ? TOOL_TIMEOUT_MSG
                    : TOOL_ERROR_MSG;

        } catch (Exception e) {
            // 捕获通用异常（如模型连接失败、参数错误，避免返回堆栈信息）
            log.error("系统处理异常（用户问题：{}）", userMessage.toString(), e);
            return SYSTEM_ERROR_MSG;
        }
    }

    /**
     * 增强：工具可用性检查（供外部健康检查接口调用，确认工具是否可用）
     * @param toolName 目标工具名称（如“exchange-rate-tool”“fee-calc-tool”）
     * @return true=工具存在且可用，false=工具不存在
     */
    public boolean isToolAvailable(String toolName) {
        if (Objects.isNull(toolName) || toolName.isEmpty() || toolCallbacks.length == 0) {
            return false;
        }
        // 匹配工具名称（忽略大小写，增强兼容性）
        for (ToolCallback tool : toolCallbacks) {
            if (tool.getToolDefinition().name().equalsIgnoreCase(toolName)) {
                return true;
            }
        }
        log.warn("工具 {} 不存在，已加载的工具名称：{}",
                toolName,
                java.util.Arrays.stream(toolCallbacks)
                        .map(tool -> tool.getToolDefinition().name())
                        .toList());
        return false;
    }
}