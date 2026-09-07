package com.ymc.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.infra.ai.AiSseEvents;
import com.ymc.chat.service.port.AiStreamListener;

/** 순수 단위 테스트 — 프레임 하나를 리스너 콜백으로 옮기는 규칙. */
class AiSseEventsTest {

    final AiSseEvents events = new AiSseEvents(new ObjectMapper());
    final List<String> calls = new ArrayList<>();
    final AtomicReference<BigDecimal> cost = new AtomicReference<>();
    final AtomicBoolean terminalSeen = new AtomicBoolean(false);

    final AiStreamListener recorder = new AiStreamListener() {
        public void onRunStarted() { calls.add("started"); }
        public void onDelta(String delta) { calls.add("delta:" + delta); }
        public void onMessageCompleted(String message) { calls.add("completed:" + message); }
        public void onRunCompleted(BigDecimal estimatedCostUsd) { cost.set(estimatedCostUsd); calls.add("run-completed"); }
        public void onRunFailed(String code, String message) { calls.add("run-failed:" + code + ":" + message); }
        public void onTransportError(Exception cause) { calls.add("transport"); }
    };

    private static ServerSentEvent<String> frame(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    @Test
    @DisplayName("성공 이벤트를 콜백으로 옮기고 run.completed에서 terminal을 세운다")
    void successFrames() {
        events.dispatch(frame("run.started", "{\"type\":\"run.started\"}"), recorder, terminalSeen);
        events.dispatch(frame("message.delta", "{\"delta\":\"안\"}"), recorder, terminalSeen);
        events.dispatch(frame("message.completed", "{\"message\":\"안녕\"}"), recorder, terminalSeen);
        assertThat(terminalSeen).isFalse();
        events.dispatch(frame("run.completed", "{\"estimated_cost_usd\":0.5}"), recorder, terminalSeen);

        assertThat(calls).containsExactly("started", "delta:안", "completed:안녕", "run-completed");
        assertThat(cost.get()).isEqualByComparingTo("0.5");
        assertThat(terminalSeen).isTrue();
    }

    @Test
    @DisplayName("run.completed에 비용이 없거나 숫자가 아니면 null로 전달한다")
    void missingCostIsNull() {
        events.dispatch(frame("run.completed", "{\"estimated_cost_usd\":\"n/a\"}"), recorder, terminalSeen);
        assertThat(calls).containsExactly("run-completed");
        assertThat(cost.get()).isNull();
    }

    @Test
    @DisplayName("run.completed data가 JSON이 아니면 성공으로 넘기지 않고 IllegalStateException")
    void malformedRunCompletedIsProtocolFailure() {
        assertThatThrownBy(() -> events.dispatch(frame("run.completed", "not-json"), recorder, terminalSeen))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("run.failed는 code와 message를 따로 전달하고 terminal을 세운다")
    void runFailed() {
        events.dispatch(frame("run.failed",
                "{\"error\":{\"code\":\"SELECTION_TOO_LARGE\",\"message\":\"too big\"}}"), recorder, terminalSeen);
        assertThat(calls).containsExactly("run-failed:SELECTION_TOO_LARGE:too big");
        assertThat(terminalSeen).isTrue();
    }

    @Test
    @DisplayName("필수 필드가 없으면 IllegalStateException — 구독 체인이 transport error로 처리한다")
    void missingFieldThrows() {
        assertThatThrownBy(() -> events.dispatch(frame("message.delta", "{}"), recorder, terminalSeen))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> events.dispatch(frame("run.failed", "{\"error\":{\"code\":\"X\"}}"), recorder, terminalSeen))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("모르는 이벤트는 무시한다")
    void unknownIgnored() {
        events.dispatch(frame("something.else", "{}"), recorder, terminalSeen);
        events.dispatch(ServerSentEvent.<String>builder().data("{}").build(), recorder, terminalSeen);
        assertThat(calls).isEmpty();
        assertThat(terminalSeen).isFalse();
    }
}
