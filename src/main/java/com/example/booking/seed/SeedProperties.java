package com.example.booking.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(String adminEmail, String adminPassword, String userEmail, String userPassword) {
}
