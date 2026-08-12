package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.PaperUploadCompletionService;
import com.ymc.support.IntegrationTest;

/** 스펙 §9의 중복 제거·동시성·수명주기 시나리오. */
class DocumentDedupIntegrationTest extends IntegrationTest {

    @Autowired
    private PaperUploadCompletionService completionService;

    private Paper completedVia(UUID ownerId, String filename,
            org.springframework.test.web.servlet.request.RequestPostProcessor jwt) throws Exception {
        Paper paper = paperRepository.save(Paper.register(ownerId, filename, Instant.now()));
        givenUploadedObject(paper);
        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(jwt))
                .andExpect(status().isOk());
        return reload(paper.getId());
    }

    @Test
    void 다른_파일명의_같은_바이트는_같은_document를_공유하고_파싱은_한_번만_발행된다() throws Exception {
        Paper a = completedVia(TEST_USER_ID, "a.pdf", userJwt());
        Paper b = completedVia(TEST_USER_ID, "b.pdf", userJwt());
        assertThat(a.getDocumentId()).isEqualTo(b.getDocumentId());
        verify(parseRequestPublisher, times(1)).publish(any(), any());
    }

    @Test
    void 다른_사용자의_같은_바이트도_같은_document를_공유한다() throws Exception {
        Paper mine = completedVia(TEST_USER_ID, "mine.pdf", userJwt());
        Paper theirs = completedVia(OTHER_USER_ID, "theirs.pdf", otherUserJwt());
        assertThat(mine.getDocumentId()).isEqualTo(theirs.getDocumentId());
        verify(parseRequestPublisher, times(1)).publish(any(), any());
    }

    @Test
    void 같은_checksum의_동시_complete는_document_하나와_발행_한_번만_만든다() throws Exception {
        Paper first = givenPendingPaper("race-1.pdf");
        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "race-2.pdf", Instant.now()));
        givenUploadedObject(first);
        givenUploadedObject(second);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        var f1 = pool.submit(() -> {
            start.await();
            return mockMvc.perform(post("/api/papers/{id}/complete", first.getId())
                    .with(userJwt())).andReturn().getResponse().getStatus();
        });
        var f2 = pool.submit(() -> {
            start.await();
            return mockMvc.perform(post("/api/papers/{id}/complete", second.getId())
                    .with(otherUserJwt())).andReturn().getResponse().getStatus();
        });
        start.countDown();
        assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        pool.shutdown();

        assertThat(reload(first.getId()).getDocumentId())
                .isEqualTo(reload(second.getId()).getDocumentId());
        verify(parseRequestPublisher, times(1)).publish(any(), any());
        assertThat(documentRepository.count()).isEqualTo(1);
    }

    /**
     * 이관 의무 2 (Task 5 리뷰) — 삭제 가드({@code paper.file_key == document.file_key}면 삭제 금지)의
     * 보호 분기가 실행되는 유일한 경로. 다른 Paper끼리의 경합(위 테스트)은 패자의 fileKey가 대표
     * 원본과 달라 실제로 지워지는 경로만 타므로, 같은 Paper로 complete 2회를 동시에 쏴야 한다.
     *
     * <p>두 스레드가 실제로 documentId==null 상태에서 동시에 linkOrCreate 경합에 들어가도록
     * fileStorage.head 호출을 CyclicBarrier로 맞춰 흔들림 없이 재현한다 — 순서가 어긋나 한쪽이
     * 먼저 전부 끝나버리면(이미 연결됨 분기) 삭제 가드 자체를 타지 않아 이 테스트가 무력화된다.
     */
    @Test
    void 같은_Paper의_동시_complete_경합에서_대표_원본은_삭제되지_않는다() throws Exception {
        Paper paper = givenPendingPaper("same-paper-race.pdf");
        givenUploadedObject(paper);

        CyclicBarrier bothArrivedAtHead = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothArrivedAtHead.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(fileStorage).head(paper.getFileKey());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        var f1 = pool.submit(() -> {
            start.await();
            return mockMvc.perform(post("/api/papers/{id}/complete", paper.getId())
                    .with(userJwt())).andReturn().getResponse().getStatus();
        });
        var f2 = pool.submit(() -> {
            start.await();
            return mockMvc.perform(post("/api/papers/{id}/complete", paper.getId())
                    .with(userJwt())).andReturn().getResponse().getStatus();
        });
        start.countDown();
        assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        pool.shutdown();

        assertThat(documentRepository.count()).isEqualTo(1);
        assertThat(reload(paper.getId()).getDocumentId()).isNotNull();
        verify(parseRequestPublisher, times(1)).publish(any(), any());
        verify(fileStorage, never()).delete(paper.getFileKey());
    }

    @Test
    void 기존_document가_COMPLETED면_새_paper는_즉시_COMPLETED를_본다() throws Exception {
        Paper first = completedVia(TEST_USER_ID, "done-1.pdf", userJwt());
        tx.execute(s -> documentRepository.markParsed(
                first.getDocumentId(), DocumentStatus.COMPLETED, null, Instant.now()));

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "done-2.pdf", Instant.now()));
        givenUploadedObject(second);
        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 기존_document가_FAILED면_새_paper도_FAILED를_보고_재파싱하지_않는다() throws Exception {
        Paper first = completedVia(TEST_USER_ID, "fail-1.pdf", userJwt());
        tx.execute(s -> documentRepository.markParsed(
                first.getDocumentId(), DocumentStatus.FAILED, "PARSE_RETRIES_EXHAUSTED",
                Instant.now()));
        clearInvocations(parseRequestPublisher);

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "fail-2.pdf", Instant.now()));
        givenUploadedObject(second);
        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        verify(parseRequestPublisher, times(0)).publish(any(), any());
    }

    @Test
    void 정체된_document는_다른_사용자의_complete가_구제_발행한다() throws Exception {
        // 발행 실패로 UPLOADED 정체를 만든다. MockMvc는 컨트롤러 밖으로 나가는 RuntimeException을
        // 그대로 되던지므로(PaperUploadCompletionIntegrationTest와 동일한 이유) 서비스를 직접 호출한다.
        org.mockito.Mockito.doThrow(new RuntimeException("SQS down")).doCallRealMethod()
                .when(parseRequestPublisher).publish(any(), any());
        Paper first = givenPendingPaper("stuck.pdf");
        givenUploadedObject(first);
        assertThatThrownBy(() -> completionService.complete(first.getId(), TEST_USER_ID))
                .isInstanceOf(RuntimeException.class);

        // 같은 바이트를 올린 다른 사용자의 complete가 구제한다 — 발행 식별자는 최초 paperId
        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "rescuer.pdf", Instant.now()));
        givenUploadedObject(second);
        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(parseRequestPublisher, times(2)).publish(first.getId(), first.getFileKey());
    }

    @Test
    void 대표_paper가_삭제돼도_다른_paper의_상태_다운로드_결과_반영은_유지된다() throws Exception {
        Paper representative = completedVia(TEST_USER_ID, "rep.pdf", userJwt());
        Paper survivor = completedVia(OTHER_USER_ID, "survivor.pdf", otherUserJwt());
        UUID documentId = representative.getDocumentId();
        UUID requestPaperId = documentRepository.findById(documentId)
                .orElseThrow().getRequestPaperId();

        paperRepository.deleteById(representative.getId());   // 대표 삭제 — document는 남는다

        assertThat(documentRepository.findById(documentId)).isPresent();
        mockMvc.perform(get("/api/papers/{id}/status", survivor.getId()).with(otherUserJwt()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/papers/{id}/download", survivor.getId()).with(otherUserJwt()))
                .andExpect(status().isOk());

        // 대표 삭제 후 도착한 결과도 requestPaperId 역조회로 반영된다
        String manifestKey = givenPackageOnS3(requestPaperId);
        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(requestPaperId, manifestKey));
        awaitConsumed(parseResultQueueUrl());
        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.COMPLETED);
    }

    /**
     * 이관 의무 3 (후보) — 발행 실패 뒤 반납(revertToUploaded)마저 실패하는 분기.
     * {@link com.ymc.paper.service.DocumentParsingStarter#revertBestEffort}가 반납 실패를 삼키므로
     * 원래 발행 실패 예외가 그대로 전파돼야 하고, document는 반납되지 못해 PROCESSING에 정체된다.
     */
    @Test
    void 발행_실패_후_반납마저_실패하면_PROCESSING에_정체되지만_원래_예외는_그대로_전파된다() throws Exception {
        Paper paper = givenPendingPaper("stuck-forever.pdf");
        givenUploadedObject(paper);
        RuntimeException publishFailure = new RuntimeException("SQS down");
        org.mockito.Mockito.doThrow(publishFailure).when(parseRequestPublisher).publish(any(), any());
        org.mockito.Mockito.doThrow(new RuntimeException("revert도 실패"))
                .when(documentTransitions).revertToUploaded(any());

        assertThatThrownBy(() -> completionService.complete(paper.getId(), TEST_USER_ID))
                .isSameAs(publishFailure);

        Document document = documentRepository
                .findById(reload(paper.getId()).getDocumentId()).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);   // 반납 실패로 정체
    }
}
