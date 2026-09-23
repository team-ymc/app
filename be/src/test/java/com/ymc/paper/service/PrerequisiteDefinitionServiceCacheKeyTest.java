package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ymc.paper.infra.ai.PrerequisiteDefinitionProperties;

/** cacheKey는 documentId·정규화된 term만으로 만들어진다 — 사용자 id가 없어 같은 문서는 사용자 간에도 캐시를 공유한다. */
class PrerequisiteDefinitionServiceCacheKeyTest {

    private final PrerequisiteDefinitionService service = new PrerequisiteDefinitionService(
            null, null, null, null, null, null, null,
            new PrerequisiteDefinitionProperties(Duration.ofSeconds(60), Duration.ofDays(30), "v1"),
            null);

    @Test
    void 정규화가_같으면_같은_키다() {
        UUID documentId = UUID.randomUUID();

        assertThat(service.cacheKey(documentId, "Softmax "))
                .isEqualTo(service.cacheKey(documentId, "softmax"));
    }

    @Test
    void 다른_문서는_다른_키다() {
        String term = "softmax";

        assertThat(service.cacheKey(UUID.randomUUID(), term))
                .isNotEqualTo(service.cacheKey(UUID.randomUUID(), term));
    }
}
