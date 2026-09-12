package com.greenhouse.iot;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Netty IoT 接入服务：承载现场网关/PLC 的 TCP 长连接。
 * Spring 容器就绪后启动，避免阻塞应用启动流程。
 */
@Slf4j
@Component
public class NettyServer {

    @Value("${greenhouse.iot.port:8600}")
    private int port;

    private final IotServerInitializer initializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyServer(IotServerInitializer initializer) {
        this.initializer = initializer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        new Thread(() -> {
            try {
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childHandler(initializer);
                serverChannel = bootstrap.bind(port).sync().channel();
                log.info("========== Netty IoT 接入服务已启动，监听端口 {} ==========", port);
                serverChannel.closeFuture().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Netty 服务线程被中断");
            } catch (Exception e) {
                log.error("Netty 服务启动失败", e);
            }
        }, "netty-server").start();
    }

    @PreDestroy
    public void stop() {
        log.info("正在关闭 Netty IoT 接入服务...");
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
