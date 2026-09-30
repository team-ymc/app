package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    @DisplayName("파싱 시도가 상한 미만이면 남은 횟수가 있다")
    void parseAttemptsLeftBelowMax() {
        Document document = Document.create(
                UUID.randomUUID(), "checksum", "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        ReflectionTestUtils.setField(document, "attempt", Document.MAX_ATTEMPTS - 1);
        assertThat(document.parseAttemptsLeft()).isTrue();

        ReflectionTestUtils.setField(document, "attempt", Document.MAX_ATTEMPTS);
        assertThat(document.parseAttemptsLeft()).isFalse();
    }

    @Test
    @DisplayName("컴파일 시도는 파싱 시도와 따로 센다")
    void compileAttemptsCountedSeparately() {
        Document document = Document.create(
                UUID.randomUUID(), "checksum", "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        ReflectionTestUtils.setField(document, "attempt", Document.MAX_ATTEMPTS);
        ReflectionTestUtils.setField(document, "compileAttempt", 1);

        assertThat(document.parseAttemptsLeft()).isFalse();
        assertThat(document.compileAttemptsLeft()).isTrue();
    }
}
