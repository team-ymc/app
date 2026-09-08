package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ymc.chat.infra.ai.ChatStreamProperties;
import com.ymc.chat.service.port.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatStreamMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    final ExecutorService relay = Executors.newSingleThreadExecutor();
    final ChatMessageTransitions transitions = mock(ChatMessageTransitions.class);
    final AtomicReference<AiStreamListener> listener = new AtomicReference<>();
    final AiAgentStreamPort port = mock(AiAgentStreamPort.class);
    ChatStreamService service;

    @BeforeEach void setup() {
        when(port.stream(any(), any())).thenAnswer(call -> {
            listener.set(call.getArgument(1));
            return (AiRunHandle) () -> {};
        });
        service = new ChatStreamService(new ChatRunMetrics(registry), port, transitions,
                new ChatStreamProperties(Duration.ofSeconds(60), Duration.ofSeconds(120),
                        Duration.ofSeconds(15), 10000, Duration.ofSeconds(150)), timer, relay);
    }
    @AfterEach void close() { timer.shutdownNow(); relay.shutdownNow(); registry.close(); }
    void begin() {
        service.begin(new SseEmitter(), new ChatStartResult(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID()), "question", null, System.nanoTime());
    }
    double count(String outcome) { return registry.counter("chat.runs", "outcome", outcome).count(); }
    double active() { return registry.get("chat.active.runs").gauge().value(); }

    @Test void successRecordedOnlyAfterPersistenceReturnsAndOnlyOnce() {
        when(transitions.complete(any(), any(), any(), any())).thenAnswer(call -> {
            assertThat(count("success")).isZero();
            assertThat(active()).isEqualTo(1);
            return true;
        });
        begin();
        listener.get().onDelta("");
        listener.get().onDelta("answer");
        listener.get().onDelta("!");
        listener.get().onMessageCompleted("answer!");
        listener.get().onRunCompleted(null);
        listener.get().onTransportError(new TimeoutException());
        listener.get().onRunCompleted(null);
        assertThat(count("success")).isEqualTo(1);
        assertThat(count("timeout")).isZero();
        assertThat(active()).isZero();
        assertThat(registry.get("chat.ttft").timer().count()).isEqualTo(1);
    }
    @Test void persistenceFailureIsErrorEvenWhenFailureTransitionAlsoThrows() {
        when(transitions.complete(any(), any(), any(), any())).thenThrow(new RuntimeException("commit"));
        when(transitions.fail(any(), any())).thenThrow(new RuntimeException("fail"));
        begin();
        listener.get().onMessageCompleted("answer");
        listener.get().onRunCompleted(null);
        assertThat(count("success")).isZero();
        assertThat(count("error")).isEqualTo(1);
        assertThat(active()).isZero();
        assertThat(registry.get("chat.ttft").timer().count()).isZero();
    }
    @Test void timeoutAndLateCallbacksOnlyProduceOneResult() {
        begin();
        listener.get().onTransportError(new TimeoutException());
        listener.get().onRunFailed("late");
        assertThat(count("timeout")).isEqualTo(1);
        assertThat(count("error")).isZero();
        assertThat(active()).isZero();
    }
    @Test void failedTransitionOwnershipIsNotSuccess() {
        begin();
        listener.get().onMessageCompleted("answer");
        listener.get().onRunCompleted(null);
        assertThat(count("success")).isZero();
        assertThat(count("error")).isEqualTo(1);
        assertThat(active()).isZero();
    }
    @Test void synchronousSubscriptionFailureReleasesGauge() {
        when(port.stream(any(), any())).thenThrow(new IllegalStateException("subscription"));
        begin();
        assertThat(count("error")).isEqualTo(1);
        assertThat(active()).isZero();
    }
}
