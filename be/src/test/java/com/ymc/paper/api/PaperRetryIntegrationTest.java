package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.PaperRetryService;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.support.FailedPaperIntegrationTest;

class PaperRetryIntegrationTest extends FailedPaperIntegrationTest {

    @Autowired
    PaperRetryService retryService;

    Paper a;
    Paper b;
    Document document;

    /** A(TEST_USER)와 B(OTHER_USER)가 같은 파일을 올렸고 파싱이 실패해 둘 다 환불된 상태. */
    @BeforeEach
    void givenBothRefunded() {
        a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        document = givenSharedFailedDocument(a, b);
        clearInvocations(parseRequestPublisher);
    }

    @Test
    @DisplayName("한 명만 재시도해 성공하면 그 Paper만 확정되고 다른 Paper는 실패로 남는다")
    void onlyRetriedPaperIsCharged() throws Exception {
        mockMvc.perform(post("/api/papers/{id}/retry", a.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(a.getId().toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(parseRequestPublisher, times(1))
                .publish(document.getRequestPaperId(), document.getFileKey());
        assertThat(documentOf(document.getId()).getAttempt()).isEqualTo(2);
        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.RESERVED);
        assertThat(reload(a.getId()).getFailedAt()).isNull();

        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);

        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(recordStatusOf(b.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        mockMvc.perform(get("/api/papers/{id}/status", a.getId()).with(userJwt()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(get("/api/papers/{id}/status", b.getId()).with(otherUserJwt()))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("이미 완료된 Document의 Paper를 재시도하면 파싱 없이 바로 확정된다")
    void retryOnCompletedDocumentSettlesAtOnce() throws Exception {
        retryService.retry(a.getId(), TEST_USER_ID);
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        clearInvocations(parseRequestPublisher);

        mockMvc.perform(post("/api/papers/{id}/retry", b.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(parseRequestPublisher, never()).publish(any(), any());
        assertThat(recordStatusOf(b.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(reload(b.getId()).getFailedAt()).isNull();
        assertThat(documentOf(document.getId()).getAttempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 중인 Document에 합류하면 요청 없이 기다리고 성공하면 둘 다 확정된다")
    void joiningRetrySettlesBothOnSuccess() throws Exception {
        retryService.retry(a.getId(), TEST_USER_ID);
        clearInvocations(parseRequestPublisher);

        mockMvc.perform(post("/api/papers/{id}/retry", b.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        verify(parseRequestPublisher, never()).publish(any(), any());
        assertThat(recordStatusOf(b.getId())).isEqualTo(UsageRecordStatus.RESERVED);

        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);

        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(recordStatusOf(b.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    @Test
    @DisplayName("재시도가 다시 실패하면 합류한 Paper까지 모두 환불된다")
    void joiningRetryRefundsBothOnFailure() {
        retryService.retry(a.getId(), TEST_USER_ID);
        retryService.retry(b.getId(), OTHER_USER_ID);

        documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.FAILED, "PARSE_FAILED");

        for (Paper paper : List.of(a, b)) {
            assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.RELEASED);
            assertThat(reload(paper.getId()).getFailedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("이미 분석 중인 Paper의 재시도는 요청 없이 현재 상태를 돌려준다")
    void retryWhileProcessingIsNoop() throws Exception {
        retryService.retry(a.getId(), TEST_USER_ID);
        clearInvocations(parseRequestPublisher);

        mockMvc.perform(post("/api/papers/{id}/retry", a.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(parseRequestPublisher, never()).publish(any(), any());
        assertThat(documentOf(document.getId()).getAttempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("시도 횟수를 모두 쓴 파일은 409이고 사용량과 환불 표시가 바뀌지 않는다")
    void rejectsWhenAttemptsExhausted() throws Exception {
        jdbcTemplate.update("update document set attempt = ? where id = ?",
                Document.MAX_ATTEMPTS, document.getId());

        mockMvc.perform(post("/api/papers/{id}/retry", a.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETRY_LIMIT_EXCEEDED"));

        verify(parseRequestPublisher, never()).publish(any(), any());
        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(reload(a.getId()).getFailedAt()).isNotNull();
        assertThat(documentOf(document.getId()).getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("등록 한도가 찼으면 429이고 Paper는 실패로 남는다")
    void rejectsWhenUsageLimitReached() throws Exception {
        for (int i = 0; i < 3; i++) {
            tx.executeWithoutResult(s -> usageService.reserve(TEST_USER_ID,
                    UsageType.PAPER_REGISTRATION, UUID.randomUUID(), UsageSourceType.PAPER));
        }

        mockMvc.perform(post("/api/papers/{id}/retry", a.getId()).with(userJwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PAPER_USAGE_LIMIT_EXCEEDED"));

        verify(parseRequestPublisher, never()).publish(any(), any());
        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(reload(a.getId()).getFailedAt()).isNotNull();
        assertThat(documentOf(document.getId()).getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청 발행이 실패하면 Document를 다시 실패로 닫고 환불한다")
    void publishFailureSettlesFailed() {
        doThrow(new IllegalStateException("SQS 장애"))
                .when(parseRequestPublisher).publish(any(), any());

        // MockMvc는 컨트롤러 밖으로 나가는 RuntimeException을 그대로 되던지므로 서비스를 직접 부른다
        assertThatThrownBy(() -> retryService.retry(a.getId(), TEST_USER_ID))
                .isInstanceOf(IllegalStateException.class);

        Document after = documentOf(document.getId());
        assertThat(after.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("PUBLISH_FAILED");
        assertThat(after.getAttempt()).isEqualTo(2);
        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(reload(a.getId()).getFailedErrorCode()).isEqualTo("PUBLISH_FAILED");
        assertThat(reload(b.getId()).getFailedErrorCode()).isEqualTo("PARSE_FAILED");
    }

    @Test
    @DisplayName("발행 실패 전에 합류한 Paper도 함께 환불된다")
    void publishFailureRefundsJoinedPaper() {
        doAnswer(invocation -> {
            // 선점 커밋과 발행 사이에 B가 합류하는 경합을 재현한다
            retryService.retry(b.getId(), OTHER_USER_ID);
            throw new IllegalStateException("SQS 장애");
        }).when(parseRequestPublisher).publish(any(), any());

        assertThatThrownBy(() -> retryService.retry(a.getId(), TEST_USER_ID))
                .isInstanceOf(IllegalStateException.class);

        for (Paper paper : List.of(a, b)) {
            assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.RELEASED);
            assertThat(reload(paper.getId()).getFailedErrorCode()).isEqualTo("PUBLISH_FAILED");
        }
        assertThat(documentOf(document.getId()).getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("환불 표시가 빠진 다른 Paper는 재시도가 시작될 때 채워져 성공 뒤에도 실패로 남는다")
    void fillsMissingMarkOnOtherPapers() throws Exception {
        jdbcTemplate.update(
                "update paper set failed_at = null, failed_error_code = null where id = ?", b.getId());

        retryService.retry(a.getId(), TEST_USER_ID);
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);

        assertThat(reload(b.getId()).getFailedAt()).isNotNull();
        assertThat(recordStatusOf(b.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        mockMvc.perform(get("/api/papers/{id}/status", b.getId()).with(otherUserJwt()))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("완료됐고 다시 할 것이 없는 Paper는 409 PAPER_NOT_RETRYABLE")
    void rejectsCompletedPaper() throws Exception {
        retryService.retry(a.getId(), TEST_USER_ID);
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);

        mockMvc.perform(post("/api/papers/{id}/retry", a.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_RETRYABLE"));
    }

    @Test
    @DisplayName("업로드가 확인되지 않은 Paper는 409 PAPER_NOT_RETRYABLE")
    void rejectsUnlinkedPaper() throws Exception {
        Paper pending = givenPendingPaper("pending.pdf");

        mockMvc.perform(post("/api/papers/{id}/retry", pending.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_RETRYABLE"));
    }

    @Test
    @DisplayName("소유자가 아니면 403, 없는 논문은 404")
    void rejectsForeignAndMissing() throws Exception {
        mockMvc.perform(post("/api/papers/{id}/retry", a.getId()).with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/papers/{id}/retry", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));

        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.RELEASED);
    }

    @RepeatedTest(3)
    @DisplayName("같은 Paper의 동시 재시도는 파싱을 한 번만 요청한다")
    void concurrentRetryPublishesOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return retryService.retry(a.getId(), TEST_USER_ID);
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        verify(parseRequestPublisher, times(1)).publish(any(), any());
        assertThat(documentOf(document.getId()).getAttempt()).isEqualTo(2);
        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.RESERVED);
    }
}
