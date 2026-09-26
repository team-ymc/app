package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SseConnectionMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final SseConnectionMetrics metrics = new SseConnectionMetrics(registry);

    double open(String stream) {
        return registry.get("sse.connections").tag("stream", stream).gauge().value();
    }

    @Test void bothStreamsStartAtZero() {
        assertThat(open("chat")).isZero();
        assertThat(open("translation")).isZero();
    }

    @Test void closeDecrementsOnlyOnceEvenWhenEveryCallbackFires() {
        Runnable chat = metrics.opened("chat");
        Runnable translation = metrics.opened("translation");
        assertThat(open("chat")).isEqualTo(1);
        assertThat(open("translation")).isEqualTo(1);

        chat.run();   // onTimeout 뒤 onCompletion처럼 두 번 닫혀도
        chat.run();
        assertThat(open("chat")).isZero();
        assertThat(open("translation")).isEqualTo(1);
    }
}
