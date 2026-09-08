package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PaperChatAccessValidatorTest extends IntegrationTest {

    @Autowired
    PaperChatAccessValidator validator;

    private Paper givenCompleted(String filename) {
        Paper paper = givenPendingPaper(filename);
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    @Test
    @DisplayName("처음 올린 논문은 requestPaperId가 자기 id다")
    void firstUploadReturnsOwnId() {
        Paper paper = givenCompleted("first.pdf");
        assertThat(validator.requireReadyRequestPaperId(paper.getId(), TEST_USER_ID)).isEqualTo(paper.getId());
    }

    @Test
    @DisplayName("기존 Document에 연결된 논문은 그 Document의 requestPaperId(다른 Paper id)를 돌려준다")
    void dedupLinkedPaperReturnsOriginalRequestId() {
        Paper first = givenCompleted("shared.pdf");
        Paper second = givenPendingPaper("shared-again.pdf");
        tx.execute(s -> paperRepository.linkDocument(second.getId(), first.getDocumentId(), Instant.now()));

        UUID aiPaperId = validator.requireReadyRequestPaperId(second.getId(), TEST_USER_ID);

        assertThat(aiPaperId).isEqualTo(first.getId());
        assertThat(aiPaperId).isNotEqualTo(second.getId());
    }

    @Test
    @DisplayName("COMPLETED가 아니면 PAPER_NOT_READY")
    void notReady() {
        Paper paper = givenProcessingPaper("processing.pdf");
        assertThatThrownBy(() -> validator.requireReadyRequestPaperId(paper.getId(), TEST_USER_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAPER_NOT_READY));
    }

    @Test
    @DisplayName("소유자가 아니면 FORBIDDEN, 없으면 PAPER_NOT_FOUND")
    void ownershipAndExistence() {
        Paper paper = givenCompleted("owned.pdf");
        assertThatThrownBy(() -> validator.requireReadyRequestPaperId(paper.getId(), OTHER_USER_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> validator.requireReadyRequestPaperId(UUID.randomUUID(), TEST_USER_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAPER_NOT_FOUND));
    }
}
