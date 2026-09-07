package com.ymc.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.chat.api.dto.ChatSelectionDto;
import com.ymc.chat.infra.ai.FakeAiTranslateStreamAdapter;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.chat.service.port.AiTranslateRequest;

class FakeAiTranslateStreamAdapterTest {

    final List<String> events = new CopyOnWriteArrayList<>();

    final AiStreamListener recorder = new AiStreamListener() {
        public void onRunStarted() { events.add("started"); }
        public void onDelta(String delta) { events.add("delta:" + delta); }
        public void onMessageCompleted(String message) { events.add("completed:" + message); }
        public void onRunCompleted(BigDecimal estimatedCostUsd) { events.add("run-completed:" + estimatedCostUsd); }
        public void onRunFailed(String code, String message) { events.add("run-failed"); }
        public void onTransportError(Exception cause) { events.add("transport-error"); }
    };

    @Test
    @DisplayName("고정 delta를 순서대로 흘리고 비용 없이 완료한다")
    void successSequence() {
        new FakeAiTranslateStreamAdapter().stream(new AiTranslateRequest("t", "p", new ChatSelectionDto(
                new ChatSelectionDto.Anchor("b", null), new ChatSelectionDto.Anchor("b", null))), recorder);

        await().atMost(Duration.ofSeconds(5)).until(() -> events.stream().anyMatch(e -> e.startsWith("run-completed")));
        assertThat(events).containsExactly(
                "started", "delta:가짜 ", "delta:번역", "delta:입니다.", "completed:가짜 번역입니다.", "run-completed:null");
    }
}
