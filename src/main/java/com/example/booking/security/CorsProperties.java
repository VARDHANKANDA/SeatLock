package com.example.booking.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (allowedOrigins.contains("*")) {
            throw new IllegalStateException("app.cors.allowed-origins must list explicit origins; '*' is not allowed");
        }
    }
}
