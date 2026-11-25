package com.demo.upimesh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
public class BridgeAuthenticationInterceptor implements HandlerInterceptor {
    private final ObjectMapper mapper;
    private final String bridgeApiKey;

    public BridgeAuthenticationInterceptor(ObjectMapper mapper,
                                           @Value("${upi.mesh.bridge-api-key:}") String bridgeApiKey) {
        this.mapper = mapper;
        this.bridgeApiKey = bridgeApiKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (bridgeApiKey.isBlank()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "bridge_auth_unconfigured", "Bridge authentication is not configured");
            return false;
        }

        String suppliedKey = request.getHeader("X-Bridge-Api-Key");
        if (suppliedKey == null || !MessageDigest.isEqual(
                bridgeApiKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "invalid_bridge_credentials", "Bridge authentication failed");
            return false;
        }
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), Map.of(
                "code", code,
                "message", message,
                "details", Map.of()
        ));
    }
}
