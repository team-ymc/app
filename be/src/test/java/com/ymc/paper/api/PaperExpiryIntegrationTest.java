package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

class PaperExpiryIntegrationTest extends IntegrationTest {

    @Autowired
    UsageService usageService;

    private Paper givenExpiredPaper(String filename) {
        Paper paper = givenPendingPaper(filename);
        tx.executeWithoutResult(s ->
                paperRepository.markExpired(paper.getId(), Instant.now()));
        return reload(paper.getId());
    }

    @Test
    @DisplayName("만료된 Paper의 상태는 EXPIRED로 파생된다")
    void expiredStatusDerived() throws Exception {
        Paper paper = givenExpiredPaper("expired.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("document가 연결된 Paper는 만료 CAS가 0행이다")
    void expireCasSkipsLinkedPaper() {
        Paper paper = givenPendingPaper("linked.pdf");
        givenLinkedDocument(paper);

        Integer updated = tx.execute(s ->
                paperRepository.markExpired(paper.getId(), Instant.now()));

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("만료된 Paper의 complete는 409 UPLOAD_EXPIRED")
    void completeRejectedAfterExpiry() throws Exception {
        Paper paper = givenExpiredPaper("expired.pdf");
        givenUploadedObject(paper);

        mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_EXPIRED"));
    }

    @Test
    @DisplayName("complete의 HEAD 중 만료가 커밋되면 연결·파싱 없이 UPLOAD_EXPIRED")
    void expiryCommittedDuringHeadPreventsLateLinkAndParsing() throws Exception {
        Paper paper = paperRepository.save(
                Paper.register(TEST_USER_ID, "expiry-race.pdf", Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, paper.getId()));
        givenUploadedObject(paper);

        CountDownLatch headEntered = new CountDownLatch(1);
        CountDownLatch allowHeadReturn = new CountDownLatch(1);
        doAnswer(invocation -> {
            headEntered.countDown();
            if (!allowHeadReturn.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("HEAD 대기 중 만료 전이가 도착하지 않았습니다.");
            }
            return invocation.callRealMethod();
        }).when(fileStorage).head(paper.getFileKey());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        var completion = pool.submit(() -> mockMvc
                .perform(post("/api/papers/{paperId}/complete", paper.getId()).with(userJwt()))
                .andReturn());
        try {
            assertThat(headEntered.await(10, TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(s -> {
                assertThat(paperRepository.markExpired(paper.getId(), Instant.now())).isEqualTo(1);
                usageService.release(UsageType.PAPER_REGISTRATION, paper.getId());
            });
            allowHeadReturn.countDown();

            var result = completion.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText())
                    .isEqualTo("UPLOAD_EXPIRED");
        } finally {
            allowHeadReturn.countDown();
            pool.shutdownNow();
        }

        Paper expired = reload(paper.getId());
        assertThat(expired.getExpiredAt()).isNotNull();
        assertThat(expired.getDocumentId()).isNull();
        assertThat(usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.PAPER_REGISTRATION, paper.getId())
                .orElseThrow().getStatus()).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(documentRepository.count()).isZero();
        verify(parseRequestPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("같은 파일명 재등록은 만료 row를 지우지 않고 나란히 추가된다")
    void reregisterKeepsExpiredRow() throws Exception {
        Paper expired = givenExpiredPaper("retry.pdf");

        mockMvc.perform(post("/api/papers")
                        .contentType("application/json")
                        .content(createPaperJson("retry.pdf"))
                        .with(userJwt()))
                .andExpect(status().isCreated());

        assertThat(paperRepository.findById(expired.getId())).isPresent();
        assertThat(paperRepository.count()).isEqualTo(2);
    }
}
