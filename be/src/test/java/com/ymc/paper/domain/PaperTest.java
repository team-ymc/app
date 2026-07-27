package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 상태 전이·불변식 단위 테스트 (tasks 2.4). 컨테이너·스프링 컨텍스트 없이 순수 도메인만 본다.
 */
class PaperTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String FILENAME = "attention-is-all-you-need.pdf";
    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("새 논문은 UPLOAD_PENDING으로 시작하고 createdAt·updatedAt이 같다")
        void startsAsUploadPending() {
            Paper paper = Paper.register(OWNER_ID, FILENAME, NOW);

            assertThat(paper.getStatus()).isEqualTo(PaperStatus.UPLOAD_PENDING);
            assertThat(paper.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(paper.getFilename()).isEqualTo(FILENAME);
            assertThat(paper.getErrorCode()).isNull();
            assertThat(paper.getCreatedAt()).isEqualTo(NOW);
            assertThat(paper.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        void register는_uploads_paperId_original_형식의_fileKey를_만든다() {
            Paper paper = Paper.register(UUID.randomUUID(), "a.pdf", Instant.now());
            assertThat(paper.getFileKey())
                    .isEqualTo("uploads/%s/original.pdf".formatted(paper.getId()));
        }

        @Test
        @DisplayName("id는 논문마다 새로 발급된다")
        void idIsUniquePerPaper() {
            Paper first = Paper.register(OWNER_ID, FILENAME, NOW);
            Paper second = Paper.register(OWNER_ID, FILENAME, NOW);

            assertThat(first.getId()).isNotEqualTo(second.getId());
        }

        @Test
        @DisplayName("빈 filename은 거부한다")
        void rejectsBlankFilename() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Paper.register(OWNER_ID, "   ", NOW));
        }
    }

    @Nested
    @DisplayName("PaperStatus")
    class Status {

        @Test
        @DisplayName("계약 enum 6종을 모두 갖는다")
        void hasAllContractValues() {
            assertThat(PaperStatus.values()).containsExactly(
                    PaperStatus.UPLOAD_PENDING,
                    PaperStatus.UPLOADED,
                    PaperStatus.PROCESSING,
                    PaperStatus.COMPLETED,
                    PaperStatus.FAILED,
                    PaperStatus.EXPIRED);
        }

        @ParameterizedTest
        @EnumSource(value = PaperStatus.class, names = {"COMPLETED", "FAILED", "EXPIRED"})
        @DisplayName("COMPLETED·FAILED·EXPIRED는 terminal이다")
        void terminalStatuses(PaperStatus status) {
            assertThat(status.isTerminal()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = PaperStatus.class, names = {"UPLOAD_PENDING", "UPLOADED", "PROCESSING"})
        @DisplayName("진행 중 상태는 terminal이 아니다")
        void nonTerminalStatuses(PaperStatus status) {
            assertThat(status.isTerminal()).isFalse();
        }
    }
}
