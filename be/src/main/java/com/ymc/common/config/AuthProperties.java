package com.ymc.common.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 auth.* 바인딩 (FT-001). 만료값은 UX 파라미터 — 설정으로 조정한다 (FT-001). */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        String feOrigin,
        boolean cookieSecure) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "auth.jwt-secret은 32바이트 이상이어야 합니다 (HS256). 첫 토큰 발급이 아니라 기동에서 실패하게 한다.");
        }
    }
}
