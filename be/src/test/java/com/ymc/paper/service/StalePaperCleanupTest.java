package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

class StalePaperCleanupTest extends IntegrationTest {

    @Autowired
    StalePaperCleanup cleanup;

    @Autowired
    UsageService usageService;

    private Paper givenReservedPaperAt(String filename, Instant createdAt) {
        Paper paper = paperRepository.save(Paper.register(TEST_USER_ID, filename, createdAt));
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, paper.getId()));
        return paper;
    }

    private UsageRecordStatus recordStatusOf(UUID paperId) {
        return usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.PAPER_REGISTRATION, paperId)
                .orElseThrow().getStatus();
    }

    @Test
    @DisplayName("오래된 UPLOAD_PENDING은 EXPIRED + 해제, 최근 것은 남긴다")
    void expiresStalePending() {
        Paper stale = givenReservedPaperAt("stale.pdf", Instant.now().minus(2, ChronoUnit.HOURS));
        Paper fresh = givenReservedPaperAt("fresh.pdf", Instant.now());

        cleanup.run();

        assertThat(reload(stale.getId()).getExpiredAt()).isNotNull();
        assertThat(recordStatusOf(stale.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(reload(fresh.getId()).getExpiredAt()).isNull();
        assertThat(recordStatusOf(fresh.getId())).isEqualTo(UsageRecordStatus.RESERVED);
    }

    @Test
    @DisplayName("오래 정체된 PROCESSING document는 FAILED + 연결 Paper 해제")
    void failsStaleProcessing() {
        Paper paper = givenReservedPaperAt("proc.pdf", Instant.now());
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        // 전이 시각을 과거로 — 스캔 기준(updated_at)을 넘긴다
        tx.executeWithoutResult(s -> documentRepository.backdateUpdatedAt(
                document.getId(), Instant.now().minus(4, ChronoUnit.HOURS)));

        cleanup.run();

        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.RELEASED);
    }

    @Test
    @DisplayName("삭제된 UPLOAD_PENDING도 정체 정리가 만료·해제한다")
    void expiresDeletedStalePending() {
        Paper stale = givenReservedPaperAt("deleted-stale.pdf", Instant.now().minus(2, ChronoUnit.HOURS));
        Integer deleted = tx.execute(s -> paperRepository.markDeleted(stale.getId(), Instant.now()));
        assertThat(deleted).isEqualTo(1);

        cleanup.run();

        assertThat(reload(stale.getId()).getExpiredAt()).isNotNull();
        assertThat(recordStatusOf(stale.getId())).isEqualTo(UsageRecordStatus.RELEASED);
    }
}
