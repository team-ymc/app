package com.ymc.chat.infra.ai;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 ai.* 바인딩 — AI 서버 연결 정보 (FT-007). */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(String baseUrl) {

    public AiProperties {
        Objects.requireNonNull(baseUrl, "ai.base-url은 필수다.");
    }
}
