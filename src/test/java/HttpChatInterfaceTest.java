import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 接口测试类
 * 对应原 WebSocket 测试类的三大功能：心跳检测、工具列表查询、对话交互
 * 依赖 Spring Boot Test 和 MockMvc，无需启动真实服务即可测试接口逻辑
 */
@SpringBootTest
@AutoConfigureMockMvc // 自动配置 MockMvc，用于模拟 HTTP 请求
public class HttpChatInterfaceTest {

    // 注入 MockMvc，用于发送 HTTP 请求并验证响应
    @Autowired
    private MockMvc mockMvc;

    /**
     * 测试 1：心跳检测接口（对应原 WebSocket 的 ping-pong 功能）
     * 接口地址：GET /mcp/api/chat/ping
     * 预期结果：响应状态 200，返回 "pong"
     */
    @Test
    void testPingInterface() throws Exception {
        // 1. 发送 GET 请求到心跳接口
        ResultActions result = mockMvc.perform(
                get("/api/chat/ping") // 接口路径（上下文路径 /mcp 由 Spring Boot Test 自动处理）
                        .contentType(MediaType.TEXT_PLAIN_VALUE)
        );

        // 2. 验证响应结果
        result.andDo(print()) // 打印请求和响应详情（便于调试）
                .andExpect(status().isOk()) // 预期响应状态码 200
                .andExpect(content().string("pong")); // 预期响应内容为 "pong"
    }

    /**
     * 测试 2：工具列表查询接口（对应原 WebSocket 的 "listTools" 动作）
     * 接口地址：GET /mcp/api/chat/tools
     * 预期结果：响应状态 200，返回 JSON 格式的工具列表（允许空数组）
     */
    @Test
    void testListToolsInterface() throws Exception {
        // 1. 发送 GET 请求到工具列表接口
        ResultActions result = mockMvc.perform(
                get("/api/chat/tools")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
        );

        // 2. 验证响应结果
        result.andDo(print())
                .andExpect(status().isOk()) // 预期状态码 200
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)) // 预期 JSON 格式
                .andExpect(jsonPath("$").isArray()); // 预期响应是数组（工具列表）
    }

    /**
     * 测试 3：对话交互接口（对应原 WebSocket 的 "chat" 动作）
     * 接口地址：POST /mcp/api/chat/query
     * 请求体：{"prompt": "java有几种基本类型？"}
     * 预期结果：响应状态 200，返回非空的模型回答
     */
    @Test
    void testChatInterface() throws Exception {
        // 1. 构建请求体（与原 WebSocket 的 chat 消息格式对齐）
        JSONObject requestBody = new JSONObject();
        requestBody.put("prompt", "java有几种基本类型？"); // 原 WebSocket 测试中的提问内容

        // 2. 发送 POST 请求到对话接口
        ResultActions result = mockMvc.perform(
                post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(requestBody.toJSONString()) // 传入 JSON 格式请求体
        );

        // 3. 验证响应结果
        result.andDo(print())
                .andExpect(status().isOk()) // 预期状态码 200
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)); // 预期文本格式响应
    }
}