package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ymc.chat.domain.TranslationRun;
import com.ymc.chat.domain.TranslationRunStatus;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

class StaleTranslationCleanupTest extends IntegrationTest {

    @Autowired
    StaleTranslationCleanup cleanup;

    @Autowired
    UsageService usageService;

    private UUID givenGenerating(Instant createdAt) {
        TranslationRun run = translationRunRepository.save(TranslationRun.start(
                TEST_USER_ID, UUID.randomUUID(), JsonNodeFactory.instance.objectNode(), createdAt));
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.AI_QUERY, run.getId(), UsageSourceType.INLINE_TRANSLATION));
        return run.getId();
    }

    @Test
    @DisplayName("deadline 지난 GENERATING을 FAILED로 내리고 예약을 해제한다. 최근 것은 그대로")
    void cleansStale() {
        UUID stale = givenGenerating(Instant.now().minus(1, ChronoUnit.HOURS));
        UUID fresh = givenGenerating(Instant.now());

        cleanup.run();

        assertThat(translationRunRepository.findById(stale).orElseThrow().getStatus()).isEqualTo(TranslationRunStatus.FAILED);
        assertThat(usageRecordRepository.findByUsageTypeAndSourceId(UsageType.AI_QUERY, stale).orElseThrow()
                .getStatus()).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(translationRunRepository.findById(fresh).orElseThrow().getStatus()).isEqualTo(TranslationRunStatus.GENERATING);
        assertThat(usageRecordRepository.findByUsageTypeAndSourceId(UsageType.AI_QUERY, fresh).orElseThrow()
                .getStatus()).isEqualTo(UsageRecordStatus.RESERVED);
    }
}
