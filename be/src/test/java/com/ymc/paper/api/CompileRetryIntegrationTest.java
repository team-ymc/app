package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.PaperRetryService;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.support.FailedPaperIntegrationTest;

class CompileRetryIntegrationTest extends FailedPaperIntegrationTest {

    @Autowired
    PaperRetryService retryService;

    Paper paper;
    String manifestKey;

    /** 파싱은 완료·확정됐고 컴파일만 실패한 상태. */
    @BeforeEach
    void givenCompileFailed() {
        paper = givenReservedPaper(TEST_USER_ID, "compile-failed.pdf");
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        manifestKey = givenTranslatedPackageOnS3(paper.getId());
        documentContentIngestService.ingest(document.getId(), manifestKey);
        documentTransitions.markCompileRequested(document.getId());
        documentTransitions.markCompiled(
                document.getId(), CompileStatus.FAILED, "LLM_RATE_LIMITED", null);
        paper = reload(paper.getId());
        clearInvocations(knowledgeCompileRequestPublisher, parseRequestPublisher);
    }

    @Test
    @DisplayName("컴파일만 다시 요청하고 사용량은 바뀌지 않는다")
    void retriesCompileOnly() throws Exception {
        mockMvc.perform(post("/api/papers/{id}/retry", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.translationStatus").value("PENDING"))
                .andExpect(jsonPath("$.knowledgeGraphStatus").value("PENDING"));

        verify(knowledgeCompileRequestPublisher, times(1)).publish(paper.getId(), manifestKey);
        verify(parseRequestPublisher, never()).publish(any(), any());
        Document after = documentOf(paper.getDocumentId());
        assertThat(after.getCompileStatus()).isEqualTo(CompileStatus.REQUESTED);
        assertThat(after.getCompileAttempt()).isEqualTo(2);
        assertThat(after.getAttempt()).isEqualTo(1);
        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    @Test
    @DisplayName("재요청한 컴파일의 완료 결과가 반영돼 번역이 준비된다")
    void retriedCompileResultIsApplied() throws Exception {
        retryService.retry(paper.getId(), TEST_USER_ID);

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(documentOf(paper.getDocumentId()).getCompileStatus())
                        .isEqualTo(CompileStatus.COMPLETED));
        mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.translationStatus").value("READY"));
    }

    @Test
    @DisplayName("컴파일 시도 횟수를 모두 썼으면 409 RETRY_LIMIT_EXCEEDED")
    void rejectsWhenCompileAttemptsExhausted() throws Exception {
        jdbcTemplate.update("update document set compile_attempt = ? where id = ?",
                Document.MAX_ATTEMPTS, paper.getDocumentId());

        mockMvc.perform(post("/api/papers/{id}/retry", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETRY_LIMIT_EXCEEDED"));

        verify(knowledgeCompileRequestPublisher, never()).publish(any(), any());
        assertThat(documentOf(paper.getDocumentId()).getCompileStatus())
                .isEqualTo(CompileStatus.FAILED);
    }

    @Test
    @DisplayName("컴파일이 이미 다시 요청된 상태면 요청 없이 현재 상태를 돌려준다")
    void retryWhileRequestedIsNoop() throws Exception {
        retryService.retry(paper.getId(), TEST_USER_ID);
        clearInvocations(knowledgeCompileRequestPublisher);

        mockMvc.perform(post("/api/papers/{id}/retry", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translationStatus").value("PENDING"));

        verify(knowledgeCompileRequestPublisher, never()).publish(any(), any());
        assertThat(documentOf(paper.getDocumentId()).getCompileAttempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("컴파일 요청 발행이 실패하면 컴파일을 다시 실패로 닫는다")
    void publishFailureClosesCompileFailed() {
        doThrow(new IllegalStateException("SQS 장애"))
                .when(knowledgeCompileRequestPublisher).publish(any(), any());

        assertThatThrownBy(() -> retryService.retry(paper.getId(), TEST_USER_ID))
                .isInstanceOf(IllegalStateException.class);

        Document after = documentOf(paper.getDocumentId());
        assertThat(after.getCompileStatus()).isEqualTo(CompileStatus.FAILED);
        assertThat(after.getCompileErrorCode()).isEqualTo("PUBLISH_FAILED");
        assertThat(after.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }
}
