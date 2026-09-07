package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ymc.chat.api.dto.ChatSelectionDto;
import com.ymc.chat.domain.TranslationRun;
import com.ymc.chat.domain.TranslationRunStatus;
import com.ymc.chat.service.TranslationCommandService;
import com.ymc.chat.service.TranslationStartResult;
import com.ymc.chat.service.TranslationStreamService;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.support.FakeAiSseServer;
import com.ymc.support.FakeAiSseServer.Script;
import com.ymc.support.IntegrationTest;

@TestPropertySource(properties = {
        "chat.stream.idle-timeout=1s",
        "chat.stream.deadline=4s",
        "chat.stream.heartbeat-interval=300ms",
        "chat.stream.max-content-length=64",
        "chat.stream.emitter-timeout=6s",
})
class TranslationRelayIntegrationTest extends IntegrationTest {

    static final FakeAiSseServer aiServer = new FakeAiSseServer();
    static final String BODY = """
            {"selection":{"start":{"blockId":"p0-b0","offset":0},"end":{"blockId":"p0-b1"}}}""";

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        aiServer.start();
        registry.add("ai.base-url", aiServer::baseUrl);
        registry.add("ai.fake-stream", () -> "false");
    }

    @AfterAll
    static void stopAiServer() {
        aiServer.close();
    }

    @Autowired
    TranslationCommandService commandService;

    @Autowired
    TranslationStreamService streamService;

    private Paper givenCompletedPaper() {
        Paper paper = givenPendingPaper("relay-" + UUID.randomUUID() + ".pdf");
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    private MvcResult startStream(Paper paper) throws Exception {
        return mockMvc.perform(post("/api/papers/{paperId}/inline-translations", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private TranslationRun awaitTerminal() {
        await().atMost(Duration.ofSeconds(10)).until(() -> translationRunRepository.findAll().stream()
                .anyMatch(r -> r.getStatus() != TranslationRunStatus.GENERATING));
        return translationRunRepository.findAll().get(0);
    }

    private String streamBody(MvcResult result) throws Exception {
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private UsageRecordStatus usageStatus(UUID translationId) {
        return usageRecordRepository.findByUsageTypeAndSourceId(UsageType.AI_QUERY, translationId)
                .orElseThrow().getStatus();
    }

    @Test
    @DisplayName("성공 — wire로 delta가 중계되고 COMPLETED·CONFIRMED, thread_id는 translationId, paper_id는 requestPaperId")
    void successOverWire() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "진짜 "),
                FakeAiSseServer.delta("t", "번역"),
                FakeAiSseServer.messageCompleted("t", "진짜 번역"),
                FakeAiSseServer.Frame.of("run.completed",
                        "{\"type\":\"run.completed\",\"thread_id\":\"t\",\"estimated_cost_usd\":0.0007}")));

        MvcResult result = startStream(paper);
        TranslationRun run = awaitTerminal();
        String stream = streamBody(result);

        assertThat(run.getStatus()).isEqualTo(TranslationRunStatus.COMPLETED);
        assertThat(run.getTranslation()).isEqualTo("진짜 번역");
        assertThat(stream).contains("event:translation.delta").contains("event:translation.completed");
        assertThat(usageStatus(run.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(usageRecordRepository.findByUsageTypeAndSourceId(UsageType.AI_QUERY, run.getId())
                .orElseThrow().getEstimatedCostUsd()).isEqualByComparingTo("0.0007");
        assertThat(aiServer.lastRequestBody()).contains("\"thread_id\":\"" + run.getId() + "\"");
        assertThat(aiServer.lastRequestBody()).contains("\"paper_id\":\"" + paper.getId() + "\"");
        assertThat(aiServer.lastRequestBody()).contains("\"block_id\":\"p0-b0\"");
    }

    @Test
    @DisplayName("run.failed SELECTION_TOO_LARGE — FAILED·RELEASED, retryable false, raw 미노출")
    void selectionTooLarge() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.runFailed("t", "SELECTION_TOO_LARGE", "upstream raw detail")));

        MvcResult result = startStream(paper);
        TranslationRun run = awaitTerminal();
        String stream = streamBody(result);

        assertThat(run.getStatus()).isEqualTo(TranslationRunStatus.FAILED);
        assertThat(usageStatus(run.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(stream).contains("\"code\":\"SELECTION_TOO_LARGE\"").contains("\"retryable\":false");
        assertThat(stream).doesNotContain("upstream raw detail");
    }

    @Test
    @DisplayName("run.failed SELECTION_RANGE_INVALID → SELECTION_INVALID(retryable false)")
    void selectionInvalidMapping() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.runFailed("t", "SELECTION_RANGE_INVALID", "x")));
        MvcResult result = startStream(paper);
        awaitTerminal();
        assertThat(streamBody(result)).contains("\"code\":\"SELECTION_INVALID\"").contains("\"retryable\":false");
    }

    @Test
    @DisplayName("run.failed의 그 외 코드 → AI_RUN_FAILED(retryable true)")
    void otherFailureMapping() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.runFailed("t", "PAPER_STRUCTURE_NOT_FOUND", "x")));
        MvcResult result = startStream(paper);
        awaitTerminal();
        assertThat(streamBody(result)).contains("\"code\":\"AI_RUN_FAILED\"").contains("\"retryable\":true");
    }

    @Test
    @DisplayName("기존 Document에 연결된 논문은 AI paper_id로 처음 올린 Paper의 id(requestPaperId)를 보낸다")
    void dedupLinkedPaperSendsRequestPaperId() throws Exception {
        Paper first = givenCompletedPaper();
        Paper second = givenPendingPaper("shared-again.pdf");
        tx.execute(s -> paperRepository.linkDocument(second.getId(), first.getDocumentId(), java.time.Instant.now()));
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.messageCompleted("t", "답"),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(reload(second.getId()));
        awaitTerminal();
        streamBody(result);

        assertThat(aiServer.lastRequestBody()).contains("\"paper_id\":\"" + first.getId() + "\"");
        assertThat(aiServer.lastRequestBody()).doesNotContain("\"paper_id\":\"" + second.getId() + "\"");
    }

    @Test
    @DisplayName("이벤트 사이 침묵이 idle timeout 초과 — AI_TIMEOUT, FAILED·RELEASED")
    void idleSilence() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(FakeAiSseServer.runStarted("t")).thenHangMillis(30_000));

        MvcResult result = startStream(paper);
        TranslationRun run = awaitTerminal();

        assertThat(run.getStatus()).isEqualTo(TranslationRunStatus.FAILED);
        assertThat(usageStatus(run.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(streamBody(result)).contains("\"code\":\"AI_TIMEOUT\"");
    }

    @Test
    @DisplayName("terminal 없이 EOF — AI_STREAM_DISCONNECTED")
    void eofWithoutTerminal() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(FakeAiSseServer.runStarted("t"), FakeAiSseServer.delta("t", "일부")));

        MvcResult result = startStream(paper);
        TranslationRun run = awaitTerminal();

        assertThat(run.getStatus()).isEqualTo(TranslationRunStatus.FAILED);
        assertThat(run.getTranslation()).isNull();
        assertThat(streamBody(result)).contains("\"code\":\"AI_STREAM_DISCONNECTED\"");
    }

    @Test
    @DisplayName("누적 상한(64자) 초과 — AI_RESPONSE_TOO_LARGE")
    void oversized() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(FakeAiSseServer.runStarted("t"), FakeAiSseServer.delta("t", "a".repeat(100))));

        MvcResult result = startStream(paper);
        awaitTerminal();
        assertThat(streamBody(result)).contains("\"code\":\"AI_RESPONSE_TOO_LARGE\"").contains("\"retryable\":false");
    }

    @Test
    @DisplayName("outbound 침묵이 heartbeat-interval을 넘으면 heartbeat가 나간다")
    void heartbeat() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                new FakeAiSseServer.Frame("message.completed",
                        "{\"type\":\"message.completed\",\"thread_id\":\"t\",\"message\":\"끝\"}", 800),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(paper);
        awaitTerminal();
        assertThat(streamBody(result)).contains("event:heartbeat").contains("\"translationId\"");
    }

    @Test
    @DisplayName("FE가 끊겨도 upstream 소비와 최종 저장은 계속된다")
    void feDisconnectStillPersists() {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                new FakeAiSseServer.Frame("message.delta",
                        "{\"type\":\"message.delta\",\"thread_id\":\"t\",\"delta\":\"느린\"}", 300),
                FakeAiSseServer.messageCompleted("t", "느린 완성"),
                FakeAiSseServer.runCompleted("t")));
        ChatSelectionDto selection = new ChatSelectionDto(
                new ChatSelectionDto.Anchor("p0-b0", 0), new ChatSelectionDto.Anchor("p0-b1", null));

        TranslationStartResult started = commandService.start(TEST_USER_ID, paper.getId(), selection);
        SseEmitter emitter = new SseEmitter(6_000L);
        streamService.begin(emitter, started, selection);
        emitter.complete();

        TranslationRun run = awaitTerminal();
        assertThat(run.getStatus()).isEqualTo(TranslationRunStatus.COMPLETED);
        assertThat(run.getTranslation()).isEqualTo("느린 완성");
        assertThat(usageStatus(run.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }
}
