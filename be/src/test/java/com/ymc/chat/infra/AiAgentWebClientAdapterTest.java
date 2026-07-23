package com.ymc.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.infra.ai.AiAgentWebClientAdapter;
import com.ymc.chat.infra.ai.ChatStreamProperties;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.support.FakeAiSseServer;
import com.ymc.support.FakeAiSseServer.Script;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** 순수 단위 테스트 — 스프링 컨텍스트·Docker 불필요. fake AI SSE 서버를 직접 상대한다. */
class AiAgentWebClientAdapterTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static FakeAiSseServer aiServer;
    static Scheduler scheduler;

    final List<String> events = new CopyOnWriteArrayList<>();

    final AiStreamListener recorder = new AiStreamListener() {
        public void onRunStarted() { events.add("started"); }
        public void onDelta(String delta) { events.add("delta:" + delta); }
        public void onMessageCompleted(String message) { events.add("completed:" + message); }
        public void onRunCompleted() { events.add("run-completed"); }
        public void onRunFailed(String error) { events.add("run-failed:" + error); }
        public void onTransportError(Exception cause) {
            events.add("transport-error:" + cause.getClass().getSimpleName());
        }
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

    private AiAgentWebClientAdapter adapter(Duration idleTimeout) {
        return new AiAgentWebClientAdapter(
                WebClient.builder().baseUrl(aiServer.baseUrl()).build(),
                scheduler,
                new ChatStreamProperties(idleTimeout, Duration.ofSeconds(30),
                        Duration.ofSeconds(15), 65536, Duration.ofSeconds(31)),
                new ObjectMapper());
    }

    @Test
    @DisplayName("성공 시퀀스를 순서대로 콜백하고, 요청 바디는 snake_case(thread_id)다")
    void successSequenceAndSnakeCaseBody() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t-1"),
                FakeAiSseServer.delta("t-1", "안녕"),
                FakeAiSseServer.delta("t-1", "하세요"),
                FakeAiSseServer.messageCompleted("t-1", "안녕하세요"),
                FakeAiSseServer.runCompleted("t-1")));

        adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-1", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.contains("run-completed"));
        assertThat(events).containsExactly(
                "started", "delta:안녕", "delta:하세요", "completed:안녕하세요", "run-completed");
        assertThat(aiServer.lastRequestBody()).contains("\"thread_id\":\"t-1\"");
        assertThat(aiServer.lastRequestBody()).contains("\"message\":\"질문\"");
        assertThat(aiServer.lastRequestBody()).doesNotContain("threadId");
    }

    @Test
    @DisplayName("run.failed를 error 문자열과 함께 전달한다")
    void runFailed() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t-2"),
                FakeAiSseServer.runFailed("t-2", "boom")));

        adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-2", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.contains("run-failed:boom"));
        assertThat(events).doesNotContain("run-completed");
    }

    @Test
    @DisplayName("이벤트 사이 침묵이 idle timeout을 넘으면 TimeoutException으로 전달된다")
    void idleSilenceTimesOut() {
        aiServer.enqueue(Script.of(FakeAiSseServer.runStarted("t-3")).thenHangMillis(10_000));

        adapter(Duration.ofMillis(300)).stream(new AiRunRequest("t-3", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.startsWith("transport-error:")));
        assertThat(events).contains("transport-error:" + TimeoutException.class.getSimpleName());
    }

    @Test
    @DisplayName("terminal 없이 EOF면 onTransportError다")
    void eofWithoutTerminal() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t-4"),
                FakeAiSseServer.delta("t-4", "일부")));

        adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-4", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.startsWith("transport-error:")));
        assertThat(events).doesNotContain("run-completed");
    }

    @Test
    @DisplayName("data JSON이 깨졌으면 onTransportError다")
    void malformedDataIsTransportError() throws InterruptedException {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.Frame.of("message.delta", "{not-json"),
                FakeAiSseServer.runCompleted("t-5")));

        adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-5", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.startsWith("transport-error:")));
        // 오류 후 구독이 취소됐으므로 후속 terminal 콜백이 오지 않는다
        Thread.sleep(300);
        assertThat(events).doesNotContain("run-completed");
        assertThat(events.stream().filter(e -> e.startsWith("transport-error:")).count()).isEqualTo(1);
    }
}
