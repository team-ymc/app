package com.ymc.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 auth.* 바인딩 (FT-001). 만료값 근거는 design 2026-07-17 §2. */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        String feOrigin,
        boolean cookieSecure) {
}
