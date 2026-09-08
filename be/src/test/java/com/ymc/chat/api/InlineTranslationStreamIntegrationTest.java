package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ymc.chat.domain.TranslationRun;
import com.ymc.chat.domain.TranslationRunStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.support.IntegrationTest;

/** 컨트롤러 → 시작 트랜잭션 → fake AI → 종결까지. wire 레벨은 TranslationRelayIntegrationTest. */
class InlineTranslationStreamIntegrationTest extends IntegrationTest {

    static final String BODY = """
            {"selection":{"start":{"blockId":"p0-b0","offset":0},"end":{"blockId":"p0-b1"}}}""";

    private Paper givenCompletedPaper() {
        Paper paper = givenPendingPaper("inline-" + UUID.randomUUID() + ".pdf");
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    private MvcResult startStream(Paper paper, String body) throws Exception {
        return mockMvc.perform(post("/api/papers/{paperId}/inline-translations", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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

    @Test
    @DisplayName("성공: started → delta → completed, run COMPLETED·translation 저장, 사용량 CONFIRMED")
    void success() throws Exception {
        Paper paper = givenCompletedPaper();

        MvcResult result = startStream(paper, BODY);
        TranslationRun run = awaitTerminal();
        String stream = streamBody(result);

        assertThat(run.getStatus()).isEqualTo(TranslationRunStatus.COMPLETED);
        assertThat(run.getTranslation()).isEqualTo("가짜 번역입니다.");
        assertThat(stream).contains("event:translation.started");
        assertThat(stream).contains("\"type\":\"translation.started\"");
        assertThat(stream).contains("\"translationId\":\"" + run.getId() + "\"");
        assertThat(stream).contains("event:translation.delta");
        assertThat(stream).contains("\"delta\":\"가짜 \"");
        assertThat(stream).contains("event:translation.completed");
        assertThat(stream).contains("\"translation\":\"가짜 번역입니다.\"");
        assertThat(stream).contains("\"contentFormat\":\"markdown\"");
        assertThat(stream).doesNotContain("sessionId").doesNotContain("messageId");
        assertThat(usageRecordRepository.findByUsageTypeAndSourceId(UsageType.AI_QUERY, run.getId()).orElseThrow()
                .getStatus()).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store, no-transform");
    }

    @Test
    @DisplayName("selection이 없으면 400 VALIDATION_ERROR — row·예약 없음")
    void missingSelection() throws Exception {
        Paper paper = givenCompletedPaper();
        mockMvc.perform(post("/api/papers/{paperId}/inline-translations", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(translationRunRepository.count()).isZero();
        assertThat(usageRecordRepository.count()).isZero();
    }

    @Test
    @DisplayName("다른 사용자 403, 없는 논문 404, 파싱 중 409 PAPER_NOT_READY")
    void preStreamErrors() throws Exception {
        Paper paper = givenCompletedPaper();
        mockMvc.perform(post("/api/papers/{paperId}/inline-translations", paper.getId())
                        .with(otherUserJwt()).accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/papers/{paperId}/inline-translations", UUID.randomUUID())
                        .with(userJwt()).accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
        Paper processing = givenProcessingPaper("processing.pdf");
        mockMvc.perform(post("/api/papers/{paperId}/inline-translations", processing.getId())
                        .with(userJwt()).accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_READY"));
    }

    @Test
    @DisplayName("진행 중인 번역이 있으면 409 TRANSLATION_IN_PROGRESS")
    void inProgress() throws Exception {
        Paper paper = givenCompletedPaper();
        translationRunRepository.save(TranslationRun.start(TEST_USER_ID, paper.getId(),
                JsonNodeFactory.instance.objectNode(), Instant.now()));

        mockMvc.perform(post("/api/papers/{paperId}/inline-translations", paper.getId())
                        .with(userJwt()).accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSLATION_IN_PROGRESS"));
        assertThat(translationRunRepository.count()).isEqualTo(1);
    }
}
