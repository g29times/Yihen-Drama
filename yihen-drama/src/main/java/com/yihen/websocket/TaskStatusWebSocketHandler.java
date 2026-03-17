package com.yihen.websocket;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yihen.entity.VideoTask;
import com.yihen.enums.TaskType;
import jakarta.websocket.EncodeException;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.EOFException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务状态WebSocket处理器
 * 用于实时推送视频生成任务状态
 */
// 定义WebSocket服务端点
@ServerEndpoint("/webSocket/{project-id}") // url路径/路径变量
@Component
@Slf4j
public class TaskStatusWebSocketHandler extends TextWebSocketHandler {

    // 存放每个项目对应的WebSocketServer对象，使用线程安全Set
    private static ConcurrentHashMap<Long, Session> sessionPools = new ConcurrentHashMap<>();

    @OnOpen //建立连接成功调用
    public void onOpen(Session session, @PathParam(value = "project-id") Long projectId) {
        sessionPools.put(projectId, session);
        session.setMaxIdleTimeout(0);

        log.info("项目:"+projectId+"连接成功");
    }

    @jakarta.websocket.OnMessage
    public void onMessage(String message, Session session, @PathParam(value = "project-id") Long projectId) {
        if (message == null) {
            return;
        }
        if ("ping".equalsIgnoreCase(message.trim())) {
            try {
                if (session != null && session.isOpen()) {
                    session.getBasicRemote().sendText("pong");
                }
            } catch (Exception e) {
                log.warn("项目:{} 心跳响应失败: {}", projectId, e.toString());
            }
        }
    }

    //关闭连接时调用
    @OnClose
    public void onClose(Session session, @PathParam(value = "project-id") Long projectId){
        // 移除下线用户连接信息
        sessionPools.remove(projectId);

        log.info("项目:"+projectId+"下线!");
    }

    @OnError
    public void onError(Session session, Throwable error, @PathParam(value = "project-id") Long projectId) {
        if (error instanceof EOFException) {
            log.warn("项目:{} WebSocket发生EOF断开: {}", projectId, error.toString());
            return;
        }

        log.warn("项目:{} WebSocket发生错误: {}", projectId, error != null ? error.toString() : "null", error);
        // 非EOF异常：主动关闭，触发前端重连，避免服务端保持异常会话
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.error("关闭发生错误的session失败", e);
            }
        }
    }

    // 给指定项目发送信息
    public void sendInfo(Long projectId, Object message) {
        Session session = sessionPools.get(projectId);
        try {
            sendMessage(session,message);
        } catch (IOException | EncodeException e) {
            throw new RuntimeException(e);
        }

    }
    @Autowired
    private ObjectMapper objectMapper;
    // 发送消息
    public void sendMessage(Session session, Object message) throws IOException, EncodeException {
        if (session != null) {
            synchronized (session) {
                // Object 转json
                String json = objectMapper.writeValueAsString(message);
                log.debug("发送数据：{}",json);
                session.getBasicRemote().sendText(json);
            }
        }
    }

}
