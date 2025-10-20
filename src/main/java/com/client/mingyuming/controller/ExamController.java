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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 比赛专用接口控制器（核心入口）
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ExamController {

    // 分类模型参数
    @Value("${llm.classify.base-url}")
    private String classifyBaseUrl;
    @Value("${llm.classify.chat-id}")
    private String classifyChatId;
    @Value("${llm.classify.session-id}")
    private String classifySessionId;
    @Value("${llm.classify.authorization}")
    private String classifyAuth;

    // 工具结果处理提示词
    @Value("${llm.tool-result-prompt}")
    private String toolResultPrompt;

    // 其他配置参数（保持不变）
    @Value("${llm.system.prompt}")
    private String systemPrompt;

    // 工具和服务
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final LLMService llmService;
    private final ChatService chatService;
    private final MysqlQueryService mysqlQueryService;
    private final ExecutorService executorService;
    private final LlmHttpUtil llmHttpUtil;

    // 构造方法注入
    @Autowired
    public ExamController(LLMService llmService,
                          ChatService chatService,
                          MysqlQueryService mysqlQueryService,
                          ExecutorService executorService, // 注入全局线程池
                          LlmHttpUtil llmHttpUtil) {
        this.llmService = llmService;
        this.chatService = chatService;
        this.mysqlQueryService = mysqlQueryService;
        this.executorService = executorService;
        this.llmHttpUtil = llmHttpUtil;
    }

    /**
     * 比赛核心接口：/api/exam
     */
    @PostMapping("/exam")
    public ResponseEntity<ExamResponseDTO> handleExamRequest(@RequestBody ExamRequestDTO requestDTO) {
        log.info("收到请求：{}", gson.toJson(requestDTO));
        String originalQuestion = requestDTO.getQuestion();
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());

        try {
            String paperType = requestDTO.getPaper();
            if ("TEST_HARD".equals(paperType) || "EXAM_HARD".equals(paperType)) {
                // HARD难度：动态工具选择
                String toolSelectionPrompt = buildToolSelectionPrompt(originalQuestion, requestDTO.getContent());
                String toolDecisionJson = llmService.generateToolDecision(toolSelectionPrompt);
                log.info("HARD难度决策结果：{}", toolDecisionJson);
                String toolResult = chatService.executeToolByDecision(toolDecisionJson, originalQuestion, requestDTO.getContent());
                responseDTO.setAnswer(toolResult);
            } else {
                // 非HARD难度：原有分类逻辑
                String requestType = llmHttpUtil.call(
                        "意图识别大模型",
                        classifyBaseUrl,
                        classifyChatId,
                        classifySessionId,
                        classifyAuth,
                        originalQuestion,
                        trimmedAnswer -> {
                            Map<String, String> answerJson = gson.fromJson(trimmedAnswer, new TypeToken<Map<String, String>>() {}.getType());
                            String type = answerJson.getOrDefault("requestType", "").trim();
                            return ("knowledge_qa".equals(type) || "tool_call".equals(type) || "data_query".equals(type))
                                    ? type : "knowledge_qa";
                        }
                );

                switch (requestType) {
                    case "data_query" -> responseDTO = handleDataQuery(requestDTO).getBody();
                    case "tool_call" -> responseDTO = handleToolCall(requestDTO).getBody();
                    case "knowledge_qa" -> responseDTO.setAnswer(handleKnowledgeQa(originalQuestion));
                    default -> responseDTO.setAnswer(handleKnowledgeQa(originalQuestion));
                }
            }

            log.info("返回应答：{}", gson.toJson(responseDTO));
            return ResponseEntity.ok(responseDTO);

        } catch (Exception e) {
            log.error("请求处理异常", e);
            responseDTO.setAnswer("系统繁忙，请稍后重试：" + e.getMessage());
            return ResponseEntity.ok(responseDTO);
        }
    }

    /**
     * 处理知识问答（复用ChatService工具）
     */
    private String handleKnowledgeQa(String question) {
        try {
            return chatService.executeKnowledgeQaTool(question);
        } catch (Exception e) {
            log.error("知识问答处理失败：{}", question, e);
            return "我没找到答案";
        }
    }

    /**
     * 处理数据查询（复用ChatService工具）
     */
    private ResponseEntity<ExamResponseDTO> handleDataQuery(ExamRequestDTO requestDTO) {
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());

        try {
            String toolResult = chatService.executeDataQueryTool(requestDTO.getQuestion(), requestDTO.getContent());
            if (toolResult.contains("路径1执行失败") && toolResult.contains("路径2执行失败")) {
                responseDTO.setAnswer("无结果");
            } else if ("00".equals(toolResult.trim())) {
                responseDTO.setAnswer(toolResult);
            } else {
                responseDTO.setAnswer(toolResult);
            }
        } catch (Exception e) {
            log.error("数据查询处理失败：{}", requestDTO.getId(), e);
            responseDTO.setAnswer("无结果");
        }

        return ResponseEntity.ok(responseDTO);
    }

    /**
     * 工具调用逻辑
     */
    private ResponseEntity<ExamResponseDTO> handleToolCall(ExamRequestDTO requestDTO) {
        ChatRequest chatRequest = new ChatRequest();
        List<Message> messages = new ArrayList<>();

        // 系统提示
        Message systemMsg = new Message();
        systemMsg.setRole("system");
        systemMsg.setContent(systemPrompt);
        messages.add(systemMsg);

        // 用户问题
        Message userMsg = new Message();
        String userQuestion = requestDTO.getQuestion() + (requestDTO.getContent() != null ? "\n补充信息：" + requestDTO.getContent() : "");
        userMsg.setRole("user");
        userMsg.setContent(userQuestion);
        messages.add(userMsg);

        chatRequest.setMessages(messages);

        // 调用工具
        String toolCmd = llmService.generateResponse(chatRequest);
        log.info("工具指令：{}", toolCmd);
        String toolResult = chatService.callToolApi(toolCmd);
        log.info("工具原始结果：{}", toolResult);

        // 处理结果
        String questionType = requestDTO.getCategory();
        String formattedPrompt = String.format(toolResultPrompt, questionType, toolResult);
        ChatRequest resultProcessRequest = new ChatRequest();
        List<Message> resultMessages = new ArrayList<>();
        resultMessages.add(new Message("system", formattedPrompt));
        resultMessages.add(new Message("user", "请根据上述规则处理结果并返回最终答案"));
        resultProcessRequest.setMessages(resultMessages);

        String finalAnswer = llmService.generateResponse(resultProcessRequest);
        log.info("格式化后答案：{}", finalAnswer);

        // 封装响应
        ExamResponseDTO responseDTO = new ExamResponseDTO();
        responseDTO.setSegments(requestDTO.getSegments());
        responseDTO.setPaper(requestDTO.getPaper());
        responseDTO.setId(requestDTO.getId());
        responseDTO.setAnswer(finalAnswer);

        return ResponseEntity.ok(responseDTO);
    }

    /**
     * 构建工具选择提示词
     */
    private String buildToolSelectionPrompt(String question, String content) {
        return String.format("""
                请根据用户问题选择合适的工具执行，可用工具如下：
                1. %s：处理需要查询数据库的问题，参数为用户问题和补充信息
                2. %s：处理常识性问题，参数为用户问题
                3. credit-card-tool：查询信用卡账单，参数需包含卡号、月份
                4. exchange-rate-tool：查询汇率，参数需包含源货币、目标货币、金额
                5. utility-bill-tool：查询水电煤账单，参数需包含户号、类型、月份
                6. user-asset-tool：查询用户资产，参数需包含用户ID
                7. payment-order-tool：创建支付订单，参数需包含金额、商户号
                8. %s：获取当前日期，无参数
                9. %s：计算数学表达式，参数为表达式字符串
                
                用户问题：%s
                补充信息：%s
                
                请返回JSON格式：{"toolName":"工具名","parameters":{"参数名":"参数值"}}，若无需工具直接返回{"toolName":"none","message":"回答内容"}
                """,
                ChatService.DATA_QUERY_TOOL,
                ChatService.KNOWLEDGE_QA_TOOL,
                ChatService.CURRENT_DATE_TOOL,
                ChatService.CALCULATOR_TOOL,
                question,
                (content == null ? "无" : content)
        );
    }
}