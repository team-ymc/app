package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.api.dto.ChatSelectionDto;
import com.ymc.chat.domain.TranslationRunStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecord;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.support.IntegrationTest;

class TranslationRunTransitionsTest extends IntegrationTest {

    @Autowired
    TranslationCommandService commandService;

    @Autowired
    TranslationRunTransitions transitions;

    private UUID givenStartedRun() {
        Paper paper = givenPendingPaper("tr.pdf");
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        return commandService.start(TEST_USER_ID, paper.getId(), new ChatSelectionDto(
                new ChatSelectionDto.Anchor("p0-b0", null), new ChatSelectionDto.Anchor("p0-b0", null)))
                .translationId();
    }

    private UsageRecord usageOf(UUID translationId) {
        return usageRecordRepository.findByUsageTypeAndSourceId(UsageType.AI_QUERY, translationId).orElseThrow();
    }

    @Test
    @DisplayName("complete는 COMPLETED·translation 저장과 사용량 CONFIRMED(비용 포함)를 한 번에 한다")
    void completeConfirms() {
        UUID id = givenStartedRun();

        assertThat(transitions.complete(id, "번역", new BigDecimal("0.002"))).isTrue();

        assertThat(translationRunRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TranslationRunStatus.COMPLETED);
        assertThat(translationRunRepository.findById(id).orElseThrow().getTranslation()).isEqualTo("번역");
        assertThat(usageOf(id).getStatus()).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(usageOf(id).getEstimatedCostUsd()).isEqualByComparingTo("0.002");
    }

    @Test
    @DisplayName("비용이 null이어도 확정된다")
    void completeWithoutCost() {
        UUID id = givenStartedRun();
        assertThat(transitions.complete(id, "번역", null)).isTrue();
        assertThat(usageOf(id).getStatus()).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(usageOf(id).getEstimatedCostUsd()).isNull();
    }

    @Test
    @DisplayName("fail은 FAILED와 사용량 RELEASED, 그 뒤 complete는 false이고 상태를 바꾸지 않는다")
    void failReleasesAndWinsRace() {
        UUID id = givenStartedRun();

        assertThat(transitions.fail(id)).isTrue();
        assertThat(transitions.complete(id, "늦은 번역", null)).isFalse();

        assertThat(translationRunRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TranslationRunStatus.FAILED);
        assertThat(translationRunRepository.findById(id).orElseThrow().getTranslation()).isNull();
        assertThat(usageOf(id).getStatus()).isEqualTo(UsageRecordStatus.RELEASED);
    }
}
