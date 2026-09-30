package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;

class PaperDerivedStatusTest {

    private static Paper paper() {
        return Paper.register(UUID.randomUUID(), "a.pdf", Instant.now());
    }

    private static Document document(DocumentStatus status) {
        Document document = Document.create(
                UUID.randomUUID(), "checksum", "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        ReflectionTestUtils.setField(document, "status", status);
        return document;
    }

    @Test
    @DisplayName("환불 표시가 있으면 Document가 완료여도 FAILED다")
    void failedMarkOverridesDocument() {
        Paper paper = paper();
        ReflectionTestUtils.setField(paper, "failedAt", Instant.now());

        assertThat(PaperDocumentViews.derivedStatus(paper, document(DocumentStatus.COMPLETED)))
                .isEqualTo(PaperStatus.FAILED);
    }

    @Test
    @DisplayName("환불 표시가 없으면 Document 상태를 따른다")
    void followsDocumentWithoutMark() {
        assertThat(PaperDocumentViews.derivedStatus(paper(), document(DocumentStatus.COMPLETED)))
                .isEqualTo(PaperStatus.COMPLETED);
        assertThat(PaperDocumentViews.derivedStatus(paper(), document(DocumentStatus.PROCESSING)))
                .isEqualTo(PaperStatus.PROCESSING);
    }

    @Test
    @DisplayName("만료는 환불 표시보다 먼저 판정한다")
    void expiredComesFirst() {
        Paper paper = paper();
        ReflectionTestUtils.setField(paper, "expiredAt", Instant.now());
        ReflectionTestUtils.setField(paper, "failedAt", Instant.now());

        assertThat(PaperDocumentViews.derivedStatus(paper, null)).isEqualTo(PaperStatus.EXPIRED);
    }
}
