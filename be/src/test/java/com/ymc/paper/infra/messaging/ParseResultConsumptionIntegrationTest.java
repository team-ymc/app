package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

/**
 * spec: parse-result-consumption (tasks 6.4). LocalStack SQS에 실제로 발행하고 리스너가 소비하게 둔다.
 */
class ParseResultConsumptionIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("파싱 성공 수신: PROCESSING → COMPLETED")
    void appliesCompletedResult() {
        Paper paper = givenProcessingPaper("success.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitStatus(paper.getId(), PaperStatus.COMPLETED);
        assertThat(reload(paper.getId()).getErrorCode()).isNull();
    }

    @Test
    @DisplayName("UPLOADED 상태의 결과도 terminal로 전이된다 (PROCESSING 커밋 전 선도착 흡수)")
    void appliesResultArrivingBeforeProcessing() {
        Paper paper = givenPendingPaper("early-result.pdf");
        paperTransitions.markUploaded(paper.getId());
        String manifestKey = givenPackageOnS3(paper.getId());

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitStatus(paper.getId(), PaperStatus.COMPLETED);
        assertThat(reload(paper.getId()).getErrorCode()).isNull();
    }

    @Test
    @DisplayName("파싱 성공 수신: 계약에 없는 필드가 있어도 무시하고 상태만 전이한다")
    void ignoresResultBody() {
        Paper paper = givenProcessingPaper("with-result.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        publishParseResult("""
                {
                  "paper_id": "%s",
                  "status": "completed",
                  "message": "ok",
                  "manifest_key": "%s",
                  "result": {"markdownKey": "papers/x/parsed.md", "media": ["a.png"]}
                }
                """.formatted(paper.getId(), manifestKey));

        awaitStatus(paper.getId(), PaperStatus.COMPLETED);
    }

    @Test
    @DisplayName("파싱 실패 수신: FAILED로 전이하고 error.code를 저장한다")
    void appliesFailedResultWithErrorCode() {
        Paper paper = givenProcessingPaper("failure.pdf");

        publishParseResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSE_RETRIES_EXHAUSTED","message":"재시도 소진"}}
                """.formatted(paper.getId()));

        awaitStatus(paper.getId(), PaperStatus.FAILED);
        assertThat(reload(paper.getId()).getErrorCode()).isEqualTo("PARSE_RETRIES_EXHAUSTED");
    }

    @Test
    @DisplayName("중복 결과 수신: 이미 COMPLETED인 레코드는 변하지 않고 메시지는 정상 소비된다")
    void duplicateResultLeavesRecordUnchanged() {
        Paper paper = givenProcessingPaper("duplicate.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());
        String message = """
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey);

        publishParseResult(message);
        awaitStatus(paper.getId(), PaperStatus.COMPLETED);
        var firstUpdatedAt = reload(paper.getId()).getUpdatedAt();

        publishParseResult(message);
        awaitConsumed(parseResultQueueUrl());

        Paper after = reload(paper.getId());
        assertThat(after.getStatus()).isEqualTo(PaperStatus.COMPLETED);
        assertThat(after.getUpdatedAt()).isEqualTo(firstUpdatedAt);   // 두 번째 수신은 아무것도 바꾸지 않았다
    }

    @Test
    @DisplayName("알 수 없는 paperId: 상태 변경 없이 정상 소비된다")
    void unknownPaperIdIsConsumed() {
        UUID unknownId = UUID.randomUUID();

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"papers/%s/manifest.json"}
                """.formatted(unknownId, unknownId));

        awaitConsumed(parseResultQueueUrl());
        assertThat(paperRepository.count()).isZero();
    }

    @Test
    @DisplayName("FAILED인데 error.code 누락: 상태 변경 없이 정상 소비된다 (비복구 계약 위반)")
    void failedWithoutErrorCodeIsConsumed() {
        Paper paper = givenProcessingPaper("no-error-code.pdf");

        publishParseResult("""
                {"paper_id":"%s","status":"failed"}
                """.formatted(paper.getId()));

        awaitConsumed(parseResultQueueUrl());
        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.PROCESSING);
    }

    @ParameterizedTest(name = "비복구 입력이라 소비만 한다: {0}")
    @ValueSource(strings = {
            "{\"paper_id\": \"%s\", \"status\": \"PROCESSING\"}",   // 파싱 서버가 낼 수 없는 status
            "{\"paper_id\": \"%s\", \"status\": \"BANANA\"}",       // 계약에 없는 status
            "{\"paper_id\": \"%s\"}",                               // status 누락
            "{\"status\": \"completed\"}",                          // paper_id 누락
            "{\"paper_id\": \"not-a-uuid\", \"status\": \"completed\"}",   // 역직렬화 실패
            "{ this is not json",                                  // malformed JSON
    })
    @DisplayName("지원하지 않는 status·필수 필드 누락·malformed JSON은 상태 변경 없이 소비된다")
    void nonRecoverableMessagesAreConsumed(String template) {
        Paper paper = givenProcessingPaper("non-recoverable.pdf");

        publishParseResult(template.contains("%s") ? template.formatted(paper.getId()) : template);

        awaitConsumed(parseResultQueueUrl());
        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.PROCESSING);
    }

    @Test
    @DisplayName("일시적 DB 장애: ack하지 않아 SQS가 재전달하고, 회복 후 전이된다")
    void transientDbFailureIsRedelivered() {
        // PROCESSING 상태 논문 생성
        Paper paper = givenProcessingPaper("transient.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        // 첫 수신은 DB 타임아웃, 그 뒤로는 정상 — 예외가 리스너 밖으로 나가야 재전달된다
        doThrow(new QueryTimeoutException("DB 타임아웃"))       // 첫 호출에서 QueryTOE
                .doCallRealMethod()                           // 실제 메서드 호출
                .when(paperTransitions).markParsed(eq(paper.getId()), any(), any());

        // 메시지 발행
        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitStatus(paper.getId(), PaperStatus.COMPLETED);
        verify(paperTransitions, atLeast(2)).markParsed(eq(paper.getId()), any(), any());
    }

    private void awaitStatus(UUID paperId, PaperStatus expected) {
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(reload(paperId).getStatus()).isEqualTo(expected));
    }
}
