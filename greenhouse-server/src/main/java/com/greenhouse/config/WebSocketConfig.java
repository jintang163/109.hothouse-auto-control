package com.greenhouse.config;

import com.greenhouse.ws.RealtimePushService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimePushService realtimePushService;

    public WebSocketConfig(RealtimePushService realtimePushService) {
        this.realtimePushService = realtimePushService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimePushService, "/ws/realtime")
                .setAllowedOrigins("*");
    }
}
