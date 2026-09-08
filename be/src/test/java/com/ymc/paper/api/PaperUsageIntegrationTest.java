package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.DocumentTransitions;
import com.ymc.paper.service.PaperDocumentLinkService;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

class PaperUsageIntegrationTest extends IntegrationTest {

    @Autowired
    PaperDocumentLinkService linkService;

    @Autowired
    UsageService usageService;

    private UsageRecordStatus recordStatusOf(UUID paperId) {
        return usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.PAPER_REGISTRATION, paperId)
                .orElseThrow().getStatus();
    }

    private Paper givenReservedPendingPaper(String filename) {
        Paper paper = givenPendingPaper(filename);
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, paper.getId(), UsageSourceType.PAPER));
        return paper;
    }

    @Test
    @DisplayName("등록은 RESERVED를 남기고, 한도(월 3회) 초과는 429 — Paper·URL 미생성")
    void registerReservesAndRejectsAtLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/papers")
                            .contentType("application/json")
                            .content(createPaperJson("p" + i + ".pdf"))
                            .with(userJwt()))
                    .andExpect(status().isCreated());
        }
        assertThat(usageRecordRepository.count()).isEqualTo(3);

        mockMvc.perform(post("/api/papers")
                        .contentType("application/json")
                        .content(createPaperJson("p4.pdf"))
                        .with(userJwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PAPER_USAGE_LIMIT_EXCEEDED"));
        assertThat(paperRepository.count()).isEqualTo(3);
        assertThat(usageRecordRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("COMPLETED document 재사용 연결은 즉시 confirm")
    void reuseCompletedConfirms() {
        Paper first = givenReservedPendingPaper("origin.pdf");
        Document document = givenLinkedDocument(first);
        documentTransitions.markProcessing(document.getId());
        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.COMPLETED, null));
        assertThat(recordStatusOf(first.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "reuse.pdf", Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                OTHER_USER_ID, UsageType.PAPER_REGISTRATION, second.getId(), UsageSourceType.PAPER));
        tx.executeWithoutResult(s -> linkService.linkOrCreate(
                second.getId(), second.getFileKey(), document.getChecksumSha256()));

        assertThat(recordStatusOf(second.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    @Test
    @DisplayName("FAILED document 재사용 연결은 즉시 release")
    void reuseFailedReleases() {
        Paper first = givenReservedPendingPaper("origin.pdf");
        Document document = givenLinkedDocument(first);
        documentTransitions.markProcessing(document.getId());
        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.FAILED, "PARSE_FAILED"));
        assertThat(recordStatusOf(first.getId())).isEqualTo(UsageRecordStatus.RELEASED);

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "reuse.pdf", Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                OTHER_USER_ID, UsageType.PAPER_REGISTRATION, second.getId(), UsageSourceType.PAPER));
        tx.executeWithoutResult(s -> linkService.linkOrCreate(
                second.getId(), second.getFileKey(), document.getChecksumSha256()));

        assertThat(recordStatusOf(second.getId())).isEqualTo(UsageRecordStatus.RELEASED);
    }

    @Test
    @DisplayName("파싱 종결이 연결된 모든 Paper를 한 번에 정산한다")
    void terminalSettlesAllLinkedPapers() {
        Paper first = givenReservedPendingPaper("a.pdf");
        Document document = givenLinkedDocument(first);
        // 파싱 중 같은 파일을 올린 두 번째 사용자
        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "b.pdf", Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                OTHER_USER_ID, UsageType.PAPER_REGISTRATION, second.getId(), UsageSourceType.PAPER));
        tx.executeWithoutResult(s ->
                paperRepository.linkDocument(second.getId(), document.getId(), Instant.now()));
        documentTransitions.markProcessing(document.getId());

        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.COMPLETED, null));

        assertThat(recordStatusOf(first.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(recordStatusOf(second.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    @Test
    @DisplayName("중복 종결 신호는 정산을 다시 만들지 않는다")
    void duplicateTerminalIsNoop() {
        Paper paper = givenReservedPendingPaper("a.pdf");
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.COMPLETED, null));

        Boolean second = tx.execute(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.FAILED, "LATE"));

        assertThat(second).isFalse();
        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    @Test
    @DisplayName("비종결 상태는 Document와 사용량을 변경하기 전에 거부한다")
    void nonTerminalStatusIsRejectedBeforeMutation() {
        Paper paper = givenReservedPendingPaper("invalid-terminal.pdf");
        Document document = givenLinkedDocument(paper);

        for (DocumentStatus invalid : new DocumentStatus[] {
                DocumentStatus.UPLOADED, DocumentStatus.PROCESSING, null}) {
            assertThatThrownBy(() -> documentTransitions.markParsedAndSettle(
                    document.getId(), invalid, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("종결 상태");
        }

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.UPLOADED);
        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.RESERVED);
    }

    /**
     * 연결(linkOrCreate)과 종결(markParsedAndSettle)이 같은 document를 동시에 건드리는 경쟁.
     * linkOrCreate의 잠금 조회(findWithLockByChecksumSha256)가 종결의 UPDATE와 같은 행을 두고
     * 경합하므로, 어느 쪽이 먼저 커밋하든 document 행 잠금이 둘을 직렬화한다 — 승자 순서와 무관하게
     * 두 Paper 모두 정산(CONFIRMED)까지 도달해야 한다 (linkOrCreate의 즉시 confirm 분기 또는
     * confirmAll 일괄 정산 중 하나로).
     */
    @RepeatedTest(5)
    @DisplayName("연결·종결 경쟁 — document 행 잠금으로 직렬화되어 두 Paper 모두 정산된다")
    void linkAndTerminalRaceSettlesBothPapers() throws Exception {
        Paper first = givenReservedPendingPaper("origin.pdf");
        Document document = givenLinkedDocument(first);
        documentTransitions.markProcessing(document.getId());

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "reuse.pdf", Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                OTHER_USER_ID, UsageType.PAPER_REGISTRATION, second.getId(), UsageSourceType.PAPER));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> linking = pool.submit(() -> {
                ready.countDown();
                awaitLatch(start);
                tx.executeWithoutResult(s -> linkService.linkOrCreate(
                        second.getId(), second.getFileKey(), document.getChecksumSha256()));
            });
            Future<?> settling = pool.submit(() -> {
                ready.countDown();
                awaitLatch(start);
                tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                        document.getId(), DocumentStatus.COMPLETED, null));
            });
            ready.await();
            start.countDown();
            linking.get(10, TimeUnit.SECONDS);
            settling.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertThat(recordStatusOf(first.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(recordStatusOf(second.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("삭제된 PROCESSING paper도 document 종결 시 정산된다")
    void settlesDeletedPaper() {
        Paper paper = givenReservedPendingPaper("deleted.pdf");
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        Integer deleted = tx.execute(s -> paperRepository.markDeleted(paper.getId(), Instant.now()));
        assertThat(deleted).isEqualTo(1);

        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.COMPLETED, null));

        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }
}
