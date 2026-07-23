package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.FakeAiSseServer;
import com.ymc.support.FakeAiSseServer.Script;
import com.ymc.support.IntegrationTest;

/**
 * 실제 WebClient 어댑터 + fake AI SSE 서버로 wire 레벨을 검증한다.
 * ai.fake-stream을 뒤집으므로 베이스와 다른 컨텍스트(컨테이너 한 벌 추가)가 뜬다 — 의도된 비용.
 */
@TestPropertySource(properties = {
        "chat.stream.idle-timeout=1s",
        "chat.stream.deadline=4s",
        "chat.stream.heartbeat-interval=300ms",
        "chat.stream.max-content-length=64",
        "chat.stream.emitter-timeout=6s",
})
class AiRelayIntegrationTest extends IntegrationTest {

    static final FakeAiSseServer aiServer = new FakeAiSseServer();

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        aiServer.start();
        registry.add("ai.base-url", aiServer::baseUrl);
        registry.add("ai.fake-stream", () -> "false"); // 베이스의 fake 활성화를 뒤집는다 (최우선순위)
    }

    @AfterAll
    static void stopAiServer() {
        aiServer.close();
    }

    @Autowired
    com.ymc.chat.service.ChatCommandService chatCommandService;

    @Autowired
    com.ymc.chat.service.ChatStreamService chatStreamService;

    private Paper givenCompletedPaper() {
        Paper paper = givenProcessingPaper("relay-" + UUID.randomUUID() + ".pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    private MvcResult startStream(Paper paper) throws Exception {
        return mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMessageId", UUID.randomUUID().toString(),
                                "content", "질문"))))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private ChatMessage awaitAssistantTerminal() {
        await().atMost(Duration.ofSeconds(10)).until(() -> chatMessageRepository.findAll().stream()
                .anyMatch(m -> m.getRole() == ChatMessageRole.ASSISTANT
                        && m.getStatus() != ChatMessageStatus.GENERATING));
        return chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT).findFirst().orElseThrow();
    }

    private String streamBody(MvcResult result) throws Exception {
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("성공 스트림 — 실제 wire로 delta가 중계되고 COMPLETED가 저장된다")
    void successOverWire() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "진짜 "),
                FakeAiSseServer.delta("t", "응답"),
                FakeAiSseServer.messageCompleted("t", "진짜 응답"),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(assistant.getContent()).isEqualTo("진짜 응답");
        String stream = streamBody(result);
        assertThat(stream).contains("event:message.delta");
        assertThat(stream).contains("event:message.completed");
    }

    @Test
    @DisplayName("run.failed — FAILED 저장, raw error 미노출, AI_RUN_FAILED")
    void runFailedOverWire() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.runFailed("t", "upstream raw detail")));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        String stream = streamBody(result);
        assertThat(stream).contains("\"code\":\"AI_RUN_FAILED\"");
        assertThat(stream).doesNotContain("upstream raw detail");
    }

    @Test
    @DisplayName("message.completed 없이 run.completed — AI_PROTOCOL_ERROR")
    void runCompletedWithoutMessage() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(paper);
        awaitAssistantTerminal();
        assertThat(streamBody(result)).contains("\"code\":\"AI_PROTOCOL_ERROR\"");
    }

    @Test
    @DisplayName("message.completed 후 run.completed 없이 EOF — AI_PROTOCOL_ERROR")
    void eofAfterCompleted() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.messageCompleted("t", "완성")));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(assistant.getContent()).isNull(); // partial 미저장
        assertThat(streamBody(result)).contains("\"code\":\"AI_PROTOCOL_ERROR\"");
    }

    @Test
    @DisplayName("terminal 없이 EOF — AI_STREAM_DISCONNECTED")
    void eofWithoutTerminal() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "일부")));

        MvcResult result = startStream(paper);
        awaitAssistantTerminal();
        assertThat(streamBody(result)).contains("\"code\":\"AI_STREAM_DISCONNECTED\"");
    }

    @Test
    @DisplayName("이벤트 사이 침묵이 idle timeout 초과 — AI_TIMEOUT")
    void idleSilence() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(FakeAiSseServer.runStarted("t")).thenHangMillis(30_000));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(streamBody(result)).contains("\"code\":\"AI_TIMEOUT\"");
    }

    @Test
    @DisplayName("누적 상한(64자) 초과 — AI_RESPONSE_TOO_LARGE, partial 미저장")
    void oversizedAccumulation() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "a".repeat(100))));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(assistant.getContent()).isNull();
        String stream = streamBody(result);
        assertThat(stream).contains("\"code\":\"AI_RESPONSE_TOO_LARGE\"");
        assertThat(stream).contains("\"retryable\":false");
    }

    @Test
    @DisplayName("누적 delta와 completed가 다르면 completed를 저장한다")
    void completedWinsOverAccumulated() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "가"),
                FakeAiSseServer.messageCompleted("t", "나"),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getContent()).isEqualTo("나");
        streamBody(result);
    }
}
