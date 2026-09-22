package com.ymc.paper.infra.ai;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class PrerequisiteDefinitionPropertiesTest {

    private static PrerequisiteDefinitionProperties of(String generatorVersion) {
        return new PrerequisiteDefinitionProperties(Duration.ofSeconds(5), Duration.ofDays(30), generatorVersion);
    }

    @Test
    void 해석되지_않은_플레이스홀더는_거절한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> of("${PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT}"))
                .withMessageContaining("PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT를 설정하세요");
    }

    @Test
    void 빈값은_거절한다() {
        assertThatIllegalArgumentException().isThrownBy(() -> of("  "));
    }

    @Test
    void 정상값은_통과한다() {
        assertThatNoException().isThrownBy(() -> of("v1"));
    }
}
