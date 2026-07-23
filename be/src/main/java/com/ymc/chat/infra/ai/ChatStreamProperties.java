package com.ymc.chat.infra.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 chat.stream.* 바인딩 — 스트림 타이머와 상한 (설계 §4 타이머 표).
 *
 * <p>idle(침묵 감지) < deadline(총 시한 안전망) < emitter(MVC async timeout) 순서가 지켜져야
 * 워치독이 인프라 timeout보다 먼저 발화한다. 어긋난 설정은 기동에서 실패시킨다.
 */
@ConfigurationProperties(prefix = "chat.stream")
public record ChatStreamProperties(
        Duration idleTimeout,
        Duration deadline,
        Duration heartbeatInterval,
        int maxContentLength,
        Duration emitterTimeout) {

    public ChatStreamProperties {
        if (idleTimeout.compareTo(deadline) >= 0) {
            throw new IllegalArgumentException("chat.stream.idle-timeout은 deadline보다 짧아야 합니다.");
        }
        if (deadline.compareTo(emitterTimeout) >= 0) {
            throw new IllegalArgumentException("chat.stream.deadline은 emitter-timeout보다 짧아야 합니다.");
        }
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("chat.stream.max-content-length는 양수여야 합니다.");
        }
    }
}
