package com.ymc.chat.infra;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.chat.infra.ai.ChatStreamProperties;

class ChatStreamPropertiesTest {

    private ChatStreamProperties props(Duration idle, Duration deadline, Duration heartbeat,
            int maxLen, Duration emitter) {
        return new ChatStreamProperties(idle, deadline, heartbeat, maxLen, emitter);
    }

    @Test
    @DisplayName("정상 값은 통과한다")
    void validValues() {
        assertThatCode(() -> props(Duration.ofSeconds(60), Duration.ofMinutes(10),
                Duration.ofSeconds(15), 65536, Duration.ofMinutes(11)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("음수 idle은 순서 검증을 통과하더라도 절대 하한에서 잡힌다")
    void negativeIdleRejected() {
        assertThatThrownBy(() -> props(Duration.ofSeconds(-1), Duration.ofSeconds(1),
                Duration.ofSeconds(15), 65536, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idle-timeout");
    }

    @Test
    @DisplayName("1ms 미만 heartbeat-interval(toMillis()==0)은 기동에서 잡힌다")
    void subMillisecondHeartbeatRejected() {
        assertThatThrownBy(() -> props(Duration.ofSeconds(60), Duration.ofMinutes(10),
                Duration.ofNanos(500_000), 65536, Duration.ofMinutes(11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat-interval");
    }

    @Test
    @DisplayName("null Duration은 NPE가 아니라 명확한 메시지로 잡힌다")
    void nullRejected() {
        assertThatThrownBy(() -> props(null, Duration.ofMinutes(10),
                Duration.ofSeconds(15), 65536, Duration.ofMinutes(11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idle-timeout");
    }
}
