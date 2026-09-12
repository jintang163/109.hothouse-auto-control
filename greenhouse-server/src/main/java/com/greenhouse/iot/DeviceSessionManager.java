package com.greenhouse.iot;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关会话管理：gatewaySn -> Channel。
 * 控制指令下发与离线判定都依赖这里的连接状态。
 */
@Slf4j
@Component
public class DeviceSessionManager {

    private final Map<String, Channel> sessions = new ConcurrentHashMap<>();

    public void register(String gatewaySn, Channel channel) {
        Channel old = sessions.put(gatewaySn, channel);
        if (old != null && old != channel) {
            log.info("网关 {} 重复连接，关闭旧连接", gatewaySn);
            old.close();
        }
    }

    /** 连接断开时按 channel 反查并摘除会话，返回被摘除的网关 SN */
    public Optional<String> unregisterByChannel(Channel channel) {
        for (Map.Entry<String, Channel> entry : sessions.entrySet()) {
            if (entry.getValue() == channel) {
                sessions.remove(entry.getKey(), channel);
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public boolean isOnline(String gatewaySn) {
        Channel ch = sessions.get(gatewaySn);
        return ch != null && ch.isActive();
    }

    /** 向网关下发文本消息，返回是否成功写出 */
    public boolean send(String gatewaySn, String text) {
        Channel ch = sessions.get(gatewaySn);
        if (ch == null || !ch.isActive()) {
            return false;
        }
        ch.writeAndFlush(text);
        return true;
    }
}
