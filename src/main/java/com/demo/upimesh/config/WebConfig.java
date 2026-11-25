package com.demo.upimesh.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final BridgeAuthenticationInterceptor bridgeAuthentication;

    public WebConfig(BridgeAuthenticationInterceptor bridgeAuthentication) {
        this.bridgeAuthentication = bridgeAuthentication;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(bridgeAuthentication).addPathPatterns("/api/bridge/ingest");
    }
}
