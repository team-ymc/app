package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.api.dto.ChatSelectionDto;
import com.ymc.chat.domain.TranslationRun;
import com.ymc.chat.domain.TranslationRunStatus;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.UsageBucket;
import com.ymc.plan.domain.UsageRecord;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.BucketPeriod;
import com.ymc.support.IntegrationTest;

class TranslationCommandServiceTest extends IntegrationTest {

    static final ChatSelectionDto SELECTION = new ChatSelectionDto(
            new ChatSelectionDto.Anchor("p0-b0", 0), new ChatSelectionDto.Anchor("p0-b1", null));

    @Autowired
    TranslationCommandService service;

    private Paper givenCompletedPaper(String filename) {
        Paper paper = givenPendingPaper(filename);
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    @Test
    @DisplayName("GENERATING row·selection 저장, AI 질의 예약(source_type INLINE_TRANSLATION), aiPaperId 반환")
    void startCreatesRunAndReserves() {
        Paper paper = givenCompletedPaper("t.pdf");

        TranslationStartResult started = service.start(TEST_USER_ID, paper.getId(), SELECTION);

        TranslationRun run = translationRunRepository.findById(started.translationId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(TranslationRunStatus.GENERATING);
        assertThat(run.getOwnerId()).isEqualTo(TEST_USER_ID);
        assertThat(run.getPaperId()).isEqualTo(paper.getId());
        assertThat(run.getSelection().get("start").get("blockId").asText()).isEqualTo("p0-b0");
        assertThat(run.getSelection().get("end").get("blockId").asText()).isEqualTo("p0-b1");
        assertThat(started.aiPaperId()).isEqualTo(paper.getId().toString());

        UsageRecord reserved = usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, started.translationId()).orElseThrow();
        assertThat(reserved.getStatus()).isEqualTo(UsageRecordStatus.RESERVED);
        assertThat(reserved.getSourceType()).isEqualTo(UsageSourceType.INLINE_TRANSLATION);
        assertThat(reload(paper.getId()).getLastAccessedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 사용자의 번역이 GENERATING이면 409 TRANSLATION_IN_PROGRESS, row·예약 없음")
    void secondStartRejectedWhileGenerating() {
        Paper paper = givenCompletedPaper("t.pdf");
        service.start(TEST_USER_ID, paper.getId(), SELECTION);

        assertThatThrownBy(() -> service.start(TEST_USER_ID, paper.getId(), SELECTION))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.TRANSLATION_IN_PROGRESS));
        assertThat(translationRunRepository.count()).isEqualTo(1);
        assertThat(usageRecordRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 시작 두 건 중 정확히 하나만 성공한다 (users 행 잠금)")
    void concurrentStartsSerializeOnUserLock() throws Exception {
        Paper paper = givenCompletedPaper("t.pdf");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit((Callable<Object>) () -> {
                    try {
                        return service.start(TEST_USER_ID, paper.getId(), SELECTION);
                    } catch (ApiException e) {
                        return e.code();
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).filteredOn(o -> o instanceof TranslationStartResult).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == ErrorCode.TRANSLATION_IN_PROGRESS).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(translationRunRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("월간 AI 질의 한도에 도달하면 429, row를 만들지 않는다")
    void rejectedAtUsageLimit() {
        Paper paper = givenCompletedPaper("t.pdf");
        Instant now = Instant.now();
        UsageBucket bucket = usageBucketRepository.save(UsageBucket.open(
                TEST_USER_ID, UsageType.AI_QUERY, PlanCode.FREE, BucketPeriod.startOf(now), now));
        for (int i = 0; i < 100; i++) {
            usageRecordRepository.save(UsageRecord.reserve(
                    bucket.getId(), UsageType.AI_QUERY, UUID.randomUUID(), UsageSourceType.CHAT_MESSAGE, now));
        }

        assertThatThrownBy(() -> service.start(TEST_USER_ID, paper.getId(), SELECTION))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CHAT_USAGE_LIMIT_EXCEEDED));
        assertThat(translationRunRepository.count()).isZero();
    }

    @Test
    @DisplayName("COMPLETED가 아니면 PAPER_NOT_READY, 남의 논문이면 FORBIDDEN — row 없음")
    void paperGuards() {
        Paper processing = givenProcessingPaper("p.pdf");
        assertThatThrownBy(() -> service.start(TEST_USER_ID, processing.getId(), SELECTION))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAPER_NOT_READY));
        Paper owned = givenCompletedPaper("o.pdf");
        assertThatThrownBy(() -> service.start(OTHER_USER_ID, owned.getId(), SELECTION))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(translationRunRepository.count()).isZero();
    }
}
