package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        @DisplayName("fileKey는 계약 형식(papers/{paperId}/original.pdf)이며 id와 대응한다")
        void fileKeyFollowsContractFormat() {
            Paper paper = Paper.register(OWNER_ID, FILENAME, NOW);

            assertThat(paper.getId()).isNotNull();
            assertThat(paper.getFileKey()).isEqualTo("papers/" + paper.getId() + "/original.pdf");
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
    @DisplayName("markProcessing")
    class MarkProcessing {

        @Test
        @DisplayName("UPLOADED에서만 PROCESSING으로 전이하고 updatedAt을 전이 시각으로 갱신한다")
        void transitionsFromUploaded() {
            Paper paper = uploaded();
            Instant transitionedAt = NOW.plus(5, ChronoUnit.MINUTES);

            paper.markProcessing(transitionedAt);

            assertThat(paper.getStatus()).isEqualTo(PaperStatus.PROCESSING);
            assertThat(paper.getUpdatedAt()).isEqualTo(transitionedAt);
            assertThat(paper.getCreatedAt()).isEqualTo(NOW);
        }

        @ParameterizedTest(name = "{0}에서는 PROCESSING으로 전이할 수 없다")
        @EnumSource(
                value = PaperStatus.class,
                names = "UPLOADED",
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("UPLOADED가 아닌 상태에서의 전이는 거부한다")
        void rejectsTransitionFromNonUploaded(PaperStatus status) {
            Paper paper = paperWith(status);

            assertThatIllegalStateException().isThrownBy(() -> paper.markProcessing(NOW));
            assertThat(paper.getStatus()).isEqualTo(status);
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

    /** UPLOAD_PENDING → UPLOADED는 CAS(repository) 몫이라 엔티티 메서드가 없다 — 테스트에선 리플렉션으로 세운다. */
    private static Paper uploaded() {
        return paperWith(PaperStatus.UPLOADED);
    }

    private static Paper paperWith(PaperStatus status) {
        Paper paper = Paper.register(OWNER_ID, FILENAME, NOW);
        assertThatCode(() -> {
            var field = Paper.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(paper, status);
        }).doesNotThrowAnyException();
        return paper;
    }
}
