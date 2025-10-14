import jakarta.websocket.*;
import java.net.URI;

@ClientEndpoint
public class WsTestClient {

    // 连接建立时触发
    @OnOpen
    public void onOpen(Session session) {
        System.out.println("客户端连接建立，sessionId：" + session.getId());
        try {
            // 1. 发送 ping 消息
            session.getBasicRemote().sendText("ping");
            // 2. 发送 chat 消息
            String chatMsg = "{\n" +
                    "  \"action\": \"chat\",\n" +
                    "  \"payload\": {\n" +
                    "    \"prompt\": \"java有几种基本类型？\"\n" +
                    "  }\n" +
                    "}";
            session.getBasicRemote().sendText(chatMsg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 收到服务端消息时触发
    @OnMessage
    public void onMessage(String message) {
        System.out.println("收到服务端响应：" + message);
    }

    // 连接关闭时触发
    @OnClose
    public void onClose() {
        System.out.println("客户端连接关闭");
    }

    public static void main(String[] args) throws Exception {
        // 建立 WebSocket 连接
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.connectToServer(WsTestClient.class, new URI("ws://localhost:9802/mcp/ws"));
        // 阻塞主线程，避免客户端退出
        Thread.sleep(30000);
    }
}