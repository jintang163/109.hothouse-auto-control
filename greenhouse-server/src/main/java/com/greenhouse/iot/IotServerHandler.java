package com.greenhouse.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greenhouse.service.ControlService;
import com.greenhouse.service.DeviceService;
import com.greenhouse.service.SensorDataService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * IoT 报文分发：注册 / 心跳 / 数据上报 / 状态上报 / 指令回执。
 * 无状态，可共享。
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class IotServerHandler extends SimpleChannelInboundHandler<String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeviceSessionManager sessionManager;
    private final DeviceService deviceService;
    private final SensorDataService sensorDataService;
    private final ControlService controlService;

    public IotServerHandler(DeviceSessionManager sessionManager,
                            DeviceService deviceService,
                            SensorDataService sensorDataService,
                            ControlService controlService) {
        this.sessionManager = sessionManager;
        this.deviceService = deviceService;
        this.sensorDataService = sensorDataService;
        this.controlService = controlService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String text) {
        JsonNode msg;
        try {
            msg = MAPPER.readTree(text);
        } catch (Exception e) {
            log.warn("非法报文，忽略: {}", text);
            return;
        }
        String type = msg.path("type").asText("");
        String sn = msg.path("sn").asText("");

        switch (type) {
            case "REGISTER" -> {
                sessionManager.register(sn, ctx.channel());
                deviceService.onGatewayOnline(sn);
                ctx.writeAndFlush(ack("REGISTER_ACK"));
                // 网关重连成功：补发离线期间缓存的指令
                controlService.flushOfflineQueue(sn);
                log.info("网关 {} 注册上线", sn);
            }
            case "HEARTBEAT" -> deviceService.onHeartbeat(sn);
            case "DATA" -> sensorDataService.handleData(sn, msg.path("data"));
            case "STATE" -> deviceService.onStateReport(sn, msg.path("states"));
            case "ACK" -> controlService.handleAck(
                    msg.path("commandId").asText(""),
                    msg.path("success").asBoolean(false),
                    msg.path("state").asText(null),
                    msg.path("error").asText(null));
            default -> log.warn("未知报文类型 {}，来自 {}", type, sn);
        }
    }

    private String ack(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("ts", System.currentTimeMillis());
        return node.toString();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            log.warn("连接 {} 读空闲超时，主动关闭", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开：摘除会话并标记该网关下所有设备离线
        sessionManager.unregisterByChannel(ctx.channel())
                .ifPresent(deviceService::onGatewayOffline);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("连接 {} 异常: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
}
