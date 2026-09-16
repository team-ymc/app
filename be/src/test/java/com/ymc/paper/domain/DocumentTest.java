package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DocumentTest {

    @Test
    void create는_UPLOADED로_시작한다() {
        Document doc = Document.create(UUID.randomUUID(), "A".repeat(43) + "=",
                "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(doc.getErrorCode()).isNull();
    }

    @Test
    void 필수값_null이면_거절한다() {
        assertThatThrownBy(() -> Document.create(null, "c", "k", UUID.randomUUID(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void terminal은_COMPLETED와_FAILED다() {
        assertThat(DocumentStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(DocumentStatus.FAILED.isTerminal()).isTrue();
        assertThat(DocumentStatus.UPLOADED.isTerminal()).isFalse();
        assertThat(DocumentStatus.PROCESSING.isTerminal()).isFalse();
    }

    @Test
    void 새_document는_언어와_컴파일_상태가_없고_번역_상태는_NOT_APPLICABLE이다() {
        Document doc = Document.create(UUID.randomUUID(), "A".repeat(43) + "=",
                "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        assertThat(doc.getSourceLanguage()).isNull();
        assertThat(doc.getCompileStatus()).isNull();
        assertThat(doc.getCompileErrorCode()).isNull();
        assertThat(doc.translationStatus()).isEqualTo(TranslationStatus.NOT_APPLICABLE);
    }

    @Test
    void 언어를_기록하면_영어는_PENDING이_된다() {
        Document doc = Document.create(UUID.randomUUID(), "A".repeat(43) + "=",
                "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        doc.recordSourceLanguage("en");
        assertThat(doc.translationStatus()).isEqualTo(TranslationStatus.PENDING);
        doc.recordSourceLanguage("ko");
        assertThat(doc.translationStatus()).isEqualTo(TranslationStatus.NOT_APPLICABLE);
    }
}
