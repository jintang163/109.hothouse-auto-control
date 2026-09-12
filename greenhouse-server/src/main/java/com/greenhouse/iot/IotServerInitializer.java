package com.greenhouse.iot;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 协议栈：4 字节大端长度域 + UTF-8 JSON 文本。
 * 上行：REGISTER / HEARTBEAT / DATA / STATE / ACK
 * 下行：CONTROL
 */
@Component
public class IotServerInitializer extends ChannelInitializer<SocketChannel> {

    private final IotServerHandler iotServerHandler;

    @Value("${greenhouse.iot.idle-seconds:90}")
    private int idleSeconds;

    public IotServerInitializer(IotServerHandler iotServerHandler) {
        this.iotServerHandler = iotServerHandler;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                // 拆包：最大 256KB，长度域占前 4 字节，解码后剥离长度域
                .addLast(new LengthFieldBasedFrameDecoder(256 * 1024, 0, 4, 0, 4))
                .addLast(new LengthFieldPrepender(4))
                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                .addLast(new StringEncoder(StandardCharsets.UTF_8))
                // 读空闲检测：超时未收到任何报文则判定连接失效
                .addLast(new IdleStateHandler(idleSeconds, 0, 0))
                .addLast(iotServerHandler);
    }
}
