package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.PaperUploadCompletionService;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.services.sqs.model.Message;

/**
 * spec: paper-upload-completion (tasks 4.5).
 *
 * <p>발행 실패·전이 실패처럼 컨트롤러가 5xx로 끝나는 시나리오는 MockMvc가 예외를 그대로 되던지므로
 * 서비스를 직접 호출해 "예외가 나가고 레코드는 UPLOADED에 남는다"를 검증한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaperUploadCompletionIntegrationTest extends IntegrationTest {

    private static final String FILENAME = "attention-is-all-you-need.pdf";

    @Autowired
    private PaperUploadCompletionService completionService;

    @Test
    @DisplayName("정상 완료 통보: parse-requests에 {paperId, fileKey} 발행 + PROCESSING (200)")
    void publishesParseRequestAndMovesToProcessing() throws Exception {
        Paper paper = givenPendingPaper(FILENAME);
        givenUploadedObject(paper);

        mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.PROCESSING);

        List<Message> published = receive(parseRequestQueueUrl(), 5);
        assertThat(published).hasSize(1);

        JsonNode body = objectMapper.readTree(published.get(0).body());
        assertThat(body.get("paperId").asText()).isEqualTo(paper.getId().toString());
        assertThat(body.get("fileKey").asText()).isEqualTo(paper.getFileKey());
        // 계약(parse-request.schema.json)은 additionalProperties: false — 필드가 딱 둘이어야 한다
        assertThat(body.properties()).hasSize(2);
    }

    @Test
    @DisplayName("중복 호출은 멱등: S3 HEAD도 큐 재발행도 하지 않고 현재 상태를 200으로 돌려준다")
    void duplicateCompleteIsIdempotent() throws Exception {
        Paper paper = givenPendingPaper(FILENAME);
        givenUploadedObject(paper);

        mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()))
                .andExpect(status().isOk());

        drain(parseRequestQueueUrl());
        clearInvocations(fileStorage, parseRequestPublisher);

        mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(fileStorage, never()).exists(anyString());
        verify(parseRequestPublisher, never()).publish(any(), anyString());
        assertThat(receive(parseRequestQueueUrl(), 1)).isEmpty();
    }

    @Test
    @DisplayName("동시 complete 호출: 조건부 전이에 성공한 한 건만 발행한다")
    void concurrentCompletePublishesOnce() throws Exception {
        Paper paper = givenPendingPaper(FILENAME);
        givenUploadedObject(paper);
        clearInvocations(parseRequestPublisher);

        int attempts = 4;
        CountDownLatch startLine = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Callable<PaperStatus>> calls = Collections.nCopies(attempts, () -> {
                startLine.await();
                return completionService.complete(paper.getId()).status();
            });

            List<Future<PaperStatus>> futures = calls.stream().map(pool::submit).toList();
            startLine.countDown();

            // 어느 호출도 실패하지 않는다 — 진 쪽은 재발행 없이 현재 상태를 돌려준다
            for (Future<PaperStatus> future : futures) {
                assertThat(future.get()).isIn(PaperStatus.UPLOADED, PaperStatus.PROCESSING);
            }
        }

        verify(parseRequestPublisher, times(1)).publish(any(), anyString());
        assertThat(receive(parseRequestQueueUrl(), 5)).hasSize(1);
        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.PROCESSING);
    }

    @Test
    @DisplayName("S3에 객체가 없으면 전이 없이 409 UPLOAD_NOT_FOUND — UPLOAD_PENDING 유지 (재시도 가능)")
    void rejectsWhenObjectMissing() throws Exception {
        Paper paper = givenPendingPaper(FILENAME);   // 업로드하지 않았다

        mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));

        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.UPLOAD_PENDING);
        verify(parseRequestPublisher, never()).publish(any(), anyString());
        assertThat(receive(parseRequestQueueUrl(), 1)).isEmpty();
    }

    @Test
    @DisplayName("없는 paperId: S3를 조회하지 않고 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(post("/api/papers/{paperId}/complete", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));

        verify(fileStorage, never()).exists(anyString());
    }

    @Test
    @DisplayName("큐 발행 실패: 예외가 나가고 레코드는 UPLOADED에 남으며, 정체가 WARN으로 관측된다")
    void publishFailureLeavesRecordUploaded(CapturedOutput output) throws Exception {
        Paper paper = givenPendingPaper(FILENAME);
        givenUploadedObject(paper);
        doThrow(new IllegalStateException("SQS 장애")).when(parseRequestPublisher)
                .publish(any(), anyString());

        assertThatThrownBy(() -> completionService.complete(paper.getId()))
                .isInstanceOf(IllegalStateException.class);

        // 발행 실패가 CAS 커밋을 롤백시키지 않았다 (트랜잭션 경계 분리, tasks 4.3)
        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.UPLOADED);

        // 복구하지 않기로 한 갭이므로 WARN 로그가 유일한 관측 수단이다 (design D6·Risks).
        // 이 단언이 깨진다면 정체가 조용해진 것이다 — 로그를 지우지 말고 여기부터 다시 생각할 것.
        assertThat(output).contains("파싱 요청 발행 실패").contains(paper.getId().toString());

        // 재호출해도 UPLOAD_PENDING이 아니므로 재발행하지 않는다 — MVP는 이 정체를 복구하지 않는다
        clearInvocations(parseRequestPublisher);
        mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        verify(parseRequestPublisher, never()).publish(any(), anyString());
        assertThat(receive(parseRequestQueueUrl(), 1)).isEmpty();
    }

    @Test
    @DisplayName("발행 후 PROCESSING 전이 실패: 레코드는 UPLOADED에 남고, 파싱 요청은 이미 나갔음이 WARN으로 관측된다")
    void processingCommitFailureLeavesRecordUploaded(CapturedOutput output) throws Exception {
        Paper paper = givenPendingPaper(FILENAME);
        givenUploadedObject(paper);
        doThrow(new QueryTimeoutException("DB 타임아웃")).when(paperTransitions)
                .markProcessing(paper.getId());

        assertThatThrownBy(() -> completionService.complete(paper.getId()))
                .isInstanceOf(QueryTimeoutException.class);

        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.UPLOADED);

        // 발행은 이미 성공했다 — 파싱은 진행되고, 결과가 오면 PROCESSING→ CAS가 0 row가 된다
        verify(parseRequestPublisher, times(1)).publish(any(), anyString());
        assertThat(receive(parseRequestQueueUrl(), 5)).hasSize(1);

        assertThat(output).contains("PROCESSING 전이 실패").contains(paper.getId().toString());
    }
}
