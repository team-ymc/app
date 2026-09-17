package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KnowledgeGraphStatusTest {

    @ParameterizedTest(name = "compile={0}, key={1} → {2}")
    @CsvSource(nullValues = "null", value = {
            "null,      null,                            PENDING",
            "null,      papers/p/knowledge-bundle/viz.html, PENDING",
            "REQUESTED, null,                            PENDING",
            "REQUESTED, papers/p/knowledge-bundle/viz.html, PENDING",
            "COMPLETED, papers/p/knowledge-bundle/viz.html, READY",
            "COMPLETED, null,                            FAILED",
            "COMPLETED, '',                              FAILED",
            "COMPLETED, '   ',                           FAILED",
            "FAILED,    null,                            FAILED",
            "FAILED,    papers/p/knowledge-bundle/viz.html, FAILED",
    })
    void 컴파일_상태와_키_유무로_지식_그래프_상태를_계산한다(CompileStatus compile, String key, KnowledgeGraphStatus expected) {
        assertThat(KnowledgeGraphStatus.of(compile, key)).isEqualTo(expected);
    }
}
