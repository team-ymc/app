package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.FailedPaperIntegrationTest;

class DocumentAttemptIntegrationTest extends FailedPaperIntegrationTest {

    @Autowired
    StalePaperCleanup cleanup;

    private Document givenFailedDocument() {
        Paper a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        Paper b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        return givenSharedFailedDocument(a, b);
    }

    private int markRetrying(Document document) {
        return tx.execute(s -> documentRepository.markRetrying(
                document.getId(), Document.MAX_ATTEMPTS, Instant.now()));
    }

    @Test
    @DisplayName("첫 파싱 시작은 시도 횟수를 1로 만든다")
    void firstStartCountsOne() {
        Document document = givenLinkedDocument(givenPendingPaper("first.pdf"));

        documentTransitions.markProcessing(document.getId());

        assertThat(documentOf(document.getId()).getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도 전이는 상태·횟수·시각을 바꾸고 실패 코드를 지운다")
    void retryingMovesFailedToProcessing() {
        Document failed = givenFailedDocument();
        tx.executeWithoutResult(s -> documentRepository.backdateUpdatedAt(
                failed.getId(), Instant.now().minus(1, ChronoUnit.DAYS)));
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        assertThat(markRetrying(failed)).isEqualTo(1);

        Document after = documentOf(failed.getId());
        assertThat(after.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(after.getAttempt()).isEqualTo(2);
        assertThat(after.getErrorCode()).isNull();
        assertThat(after.getUpdatedAt()).isAfter(before);
    }

    @Test
    @DisplayName("시도 횟수를 모두 쓴 Document는 재시도 전이가 일어나지 않는다")
    void retryingStopsAtMax() {
        Document failed = givenFailedDocument();
        jdbcTemplate.update("update document set attempt = ? where id = ?",
                Document.MAX_ATTEMPTS, failed.getId());

        assertThat(markRetrying(failed)).isZero();
        assertThat(documentOf(failed.getId()).getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("실패 상태가 아닌 Document는 재시도 전이가 일어나지 않는다")
    void retryingRequiresFailed() {
        Paper a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        Paper b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        Document processing = givenSharedProcessingDocument(a, b);

        assertThat(markRetrying(processing)).isZero();
        assertThat(documentOf(processing.getId()).getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("오래전에 실패한 Document를 재시도한 직후에는 정체 정리가 실패 처리하지 않는다")
    void staleCleanupSparesFreshRetry() {
        Document failed = givenFailedDocument();
        tx.executeWithoutResult(s -> documentRepository.backdateUpdatedAt(
                failed.getId(), Instant.now().minus(1, ChronoUnit.DAYS)));
        markRetrying(failed);

        cleanup.run();

        assertThat(documentOf(failed.getId()).getStatus()).isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    @DisplayName("컴파일 요청과 재요청은 컴파일 시도 횟수를 따로 센다")
    void compileAttemptsCounted() {
        Paper paper = givenProcessingPaper("compile.pdf");
        documentTransitions.markParsedAndSettle(
                paper.getDocumentId(), DocumentStatus.COMPLETED, null);

        documentTransitions.markCompileRequested(paper.getDocumentId());
        assertThat(documentOf(paper.getDocumentId()).getCompileAttempt()).isEqualTo(1);

        documentTransitions.markCompiled(
                paper.getDocumentId(), CompileStatus.FAILED, "LLM_RATE_LIMITED", null);
        Integer moved = tx.execute(s -> documentRepository.markCompileRetrying(
                paper.getDocumentId(), Document.MAX_ATTEMPTS));

        Document after = documentOf(paper.getDocumentId());
        assertThat(moved).isEqualTo(1);
        assertThat(after.getCompileStatus()).isEqualTo(CompileStatus.REQUESTED);
        assertThat(after.getCompileErrorCode()).isNull();
        assertThat(after.getCompileAttempt()).isEqualTo(2);
        assertThat(after.getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("컴파일 시도 횟수를 모두 썼으면 재요청 전이가 일어나지 않는다")
    void compileRetryingStopsAtMax() {
        Paper paper = givenProcessingPaper("compile-max.pdf");
        documentTransitions.markParsedAndSettle(
                paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentTransitions.markCompileRequested(paper.getDocumentId());
        documentTransitions.markCompiled(
                paper.getDocumentId(), CompileStatus.FAILED, "LLM_RATE_LIMITED", null);
        jdbcTemplate.update("update document set compile_attempt = ? where id = ?",
                Document.MAX_ATTEMPTS, paper.getDocumentId());

        Integer moved = tx.execute(s -> documentRepository.markCompileRetrying(
                paper.getDocumentId(), Document.MAX_ATTEMPTS));

        assertThat(moved).isZero();
        assertThat(documentOf(paper.getDocumentId()).getCompileStatus())
                .isEqualTo(CompileStatus.FAILED);
    }

    @Test
    @DisplayName("다른 Paper의 빈 환불 표시만 채우고 재시도한 Paper는 건드리지 않는다")
    void marksOthersOnly() {
        Paper a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        Paper b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        Document failed = givenSharedFailedDocument(a, b);
        jdbcTemplate.update("update paper set failed_at = null, failed_error_code = null");

        tx.executeWithoutResult(s -> paperRepository.markFailedOthers(
                failed.getId(), a.getId(), "PARSE_FAILED", Instant.now()));

        assertThat(reload(a.getId()).getFailedAt()).isNull();
        assertThat(reload(b.getId()).getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("환불 표시를 지우고 다시 찍는다")
    void clearsAndMarksOnePaper() {
        Paper a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        Paper b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        givenSharedFailedDocument(a, b);

        Integer cleared = tx.execute(s -> paperRepository.clearFailed(a.getId(), Instant.now()));
        assertThat(cleared).isEqualTo(1);
        assertThat(reload(a.getId()).getFailedAt()).isNull();
        assertThat(reload(a.getId()).getFailedErrorCode()).isNull();

        Integer marked = tx.execute(s ->
                paperRepository.markFailed(a.getId(), "PARSE_FAILED", Instant.now()));
        assertThat(marked).isEqualTo(1);
        assertThat(reload(a.getId()).getFailedAt()).isNotNull();
    }
}
