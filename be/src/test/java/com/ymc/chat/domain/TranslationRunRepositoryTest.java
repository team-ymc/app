package com.ymc.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ymc.support.IntegrationTest;

class TranslationRunRepositoryTest extends IntegrationTest {

    @Autowired
    TranslationRunRepository translationRunRepository;

    private TranslationRun givenGenerating(UUID ownerId) {
        return translationRunRepository.save(TranslationRun.start(
                ownerId, UUID.randomUUID(),
                JsonNodeFactory.instance.objectNode().put("start", "p0-b0"), Instant.now()));
    }

    @Test
    @DisplayName("GENERATING → COMPLETED 전이는 한 번만 1 row를 얻고 translation을 저장한다")
    void completeIsCas() {
        TranslationRun run = givenGenerating(TEST_USER_ID);

        // markCompleted/markFailed는 @Modifying 쿼리라 활성 트랜잭션이 필요하다 (JPA executeUpdate 제약) —
        // paperRepository.linkDocument 테스트와 같은 방식으로 tx로 감싼다.
        Integer completed = tx.execute(s -> translationRunRepository.markCompleted(run.getId(), "번역", Instant.now()));
        assertThat(completed).isEqualTo(1);
        Integer completedAgain = tx.execute(s -> translationRunRepository.markCompleted(run.getId(), "다시", Instant.now()));
        assertThat(completedAgain).isEqualTo(0);
        Integer failedAfterComplete = tx.execute(s -> translationRunRepository.markFailed(run.getId(), Instant.now()));
        assertThat(failedAfterComplete).isEqualTo(0);

        TranslationRun saved = translationRunRepository.findById(run.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TranslationRunStatus.COMPLETED);
        assertThat(saved.getTranslation()).isEqualTo("번역");
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("FAILED 전이는 translation을 남기지 않는다")
    void failKeepsNoTranslation() {
        TranslationRun run = givenGenerating(TEST_USER_ID);
        Integer failed = tx.execute(s -> translationRunRepository.markFailed(run.getId(), Instant.now()));
        assertThat(failed).isEqualTo(1);
        TranslationRun saved = translationRunRepository.findById(run.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TranslationRunStatus.FAILED);
        assertThat(saved.getTranslation()).isNull();
    }

    @Test
    @DisplayName("소유자별 GENERATING 존재 여부와 selection jsonb 왕복")
    void existsAndSelectionRoundTrip() {
        TranslationRun run = givenGenerating(TEST_USER_ID);
        assertThat(translationRunRepository.existsByOwnerIdAndStatus(TEST_USER_ID, TranslationRunStatus.GENERATING)).isTrue();
        assertThat(translationRunRepository.existsByOwnerIdAndStatus(OTHER_USER_ID, TranslationRunStatus.GENERATING)).isFalse();
        assertThat(translationRunRepository.findById(run.getId()).orElseThrow().getSelection().get("start").asText())
                .isEqualTo("p0-b0");
    }
}
