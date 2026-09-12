package com.greenhouse.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时推送：管理端/大屏订阅 /ws/realtime。
 * 事件类型：sensor(传感数据) device(设备状态) alarm(告警) command(指令状态)
 *
 * <p>注意：必须复用 Spring 容器中已注册 JavaTimeModule 的 ObjectMapper，
 * 否则实体中的 LocalDateTime（如 device.lastSeenAt、command.createdAt）无法序列化。
 */
@Slf4j
@Component
public class RealtimePushService extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public RealtimePushService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.debug("实时推送客户端接入，当前 {} 个", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(String event, Object data) {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("event", event);
            node.set("data", objectMapper.valueToTree(data));
            String text = node.toString();
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(text));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("实时推送失败: {}", e.getMessage());
        }
    }
}
