package com.ymc.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.api.dto.ChatSelectionDto;
import com.ymc.chat.infra.ai.AiSseEvents;
import com.ymc.chat.infra.ai.AiTranslateWebClientAdapter;
import com.ymc.chat.infra.ai.ChatStreamProperties;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.chat.service.port.AiTranslateRequest;
import com.ymc.support.FakeAiSseServer;
import com.ymc.support.FakeAiSseServer.Script;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** 순수 단위 테스트 — 번역 경로·요청 바디 형식과 콜백 순서. */
class AiTranslateWebClientAdapterTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static FakeAiSseServer aiServer;
    static Scheduler scheduler;

    final List<String> events = new CopyOnWriteArrayList<>();

    final AiStreamListener recorder = new AiStreamListener() {
        public void onRunStarted() { events.add("started"); }
        public void onDelta(String delta) { events.add("delta:" + delta); }
        public void onMessageCompleted(String message) { events.add("completed:" + message); }
        public void onRunCompleted(BigDecimal estimatedCostUsd) { events.add("run-completed"); }
        public void onRunFailed(String code, String message) { events.add("run-failed:" + code + ":" + message); }
        public void onTransportError(Exception cause) { events.add("transport-error:" + cause.getClass().getSimpleName()); }
    };

    @BeforeAll
    static void startServer() {
        aiServer = new FakeAiSseServer();
        aiServer.start();
        scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor());
    }

    @AfterAll
    static void stopServer() {
        aiServer.close();
        scheduler.dispose();
    }

    private AiTranslateWebClientAdapter adapter() {
        return new AiTranslateWebClientAdapter(
                WebClient.builder().baseUrl(aiServer.baseUrl()).build(),
                scheduler,
                new ChatStreamProperties(Duration.ofSeconds(5), Duration.ofSeconds(30),
                        Duration.ofSeconds(15), 65536, Duration.ofSeconds(31)),
                new AiSseEvents(new ObjectMapper()));
    }

    private static AiTranslateRequest request(String threadId) {
        return new AiTranslateRequest(threadId, "paper-1", new ChatSelectionDto(
                new ChatSelectionDto.Anchor("p0002-b0000", 0), new ChatSelectionDto.Anchor("p0002-b0003", null)));
    }

    @Test
    @DisplayName("inline-translate 경로로 snake_case 단일 selection을 보내고 성공 시퀀스를 콜백한다")
    void successAndBody() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t-1"),
                FakeAiSseServer.delta("t-1", "번역 "),
                FakeAiSseServer.messageCompleted("t-1", "번역 결과"),
                FakeAiSseServer.runCompleted("t-1")));

        adapter().stream(request("t-1"), recorder);

        await().atMost(WAIT).until(() -> events.contains("run-completed"));
        assertThat(events).containsExactly("started", "delta:번역 ", "completed:번역 결과", "run-completed");
        assertThat(aiServer.lastRequestPath()).isEqualTo("/api/v1/agents/inline-translate-agent/runs/stream");
        String body = aiServer.lastRequestBody();
        assertThat(body).contains("\"thread_id\":\"t-1\"");
        assertThat(body).contains("\"paper_id\":\"paper-1\"");
        assertThat(body).contains("\"selection\":{\"start\":{\"block_id\":\"p0002-b0000\",\"offset\":0},\"end\":{\"block_id\":\"p0002-b0003\"}}");
        assertThat(body).doesNotContain("blockId").doesNotContain("selections").doesNotContain("message");
    }

    @Test
    @DisplayName("run.failed의 code와 message를 분리해 전달한다")
    void runFailed() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t-2"),
                FakeAiSseServer.runFailed("t-2", "SELECTION_TOO_LARGE", "raw")));

        adapter().stream(request("t-2"), recorder);

        await().atMost(WAIT).until(() -> events.contains("run-failed:SELECTION_TOO_LARGE:raw"));
        assertThat(events).doesNotContain("run-completed");
    }
}
