package com.ymc.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.chat.infra.ai.FakeAiAgentStreamAdapter;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

/** 순수 단위 테스트 — 스프링 컨텍스트 불필요. */
class FakeAiAgentStreamAdapterTest {

    final List<String> events = new CopyOnWriteArrayList<>();

    final AiStreamListener recorder = new AiStreamListener() {
        public void onRunStarted() { events.add("started"); }
        public void onDelta(String delta) { events.add("delta:" + delta); }
        public void onMessageCompleted(String message) { events.add("completed:" + message); }
        public void onRunCompleted() { events.add("run-completed"); }
        public void onRunFailed(String error) { events.add("run-failed:" + error); }
        public void onTransportError(Exception cause) { events.add("transport-error"); }
    };

    @Test
    @DisplayName("성공 시퀀스를 순서대로 콜백하고, delta 누적과 최종 답변이 일치한다")
    void successSequence() {
        new FakeAiAgentStreamAdapter().stream(new AiRunRequest("t-1", "질문"), recorder);

        await().atMost(Duration.ofSeconds(5)).until(() -> events.contains("run-completed"));

        assertThat(events).containsExactly(
                "started",
                "delta:가짜 ",
                "delta:응답",
                "delta:입니다.",
                "completed:가짜 응답입니다.",
                "run-completed");
    }
}
