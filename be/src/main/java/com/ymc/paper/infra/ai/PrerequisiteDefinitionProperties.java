package com.ymc.paper.infra.ai;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 선행지식 설명 생성 설정. generatorVersion은 기본값이 없어 누락되면 기동에 실패한다. */
@ConfigurationProperties(prefix = "prerequisite.definition")
public record PrerequisiteDefinitionProperties(Duration timeout, Duration cacheTtl, String generatorVersion) {

    public PrerequisiteDefinitionProperties {
        Objects.requireNonNull(timeout, "prerequisite.definition.timeout은 필수다.");
        Objects.requireNonNull(cacheTtl, "prerequisite.definition.cache-ttl은 필수다.");
        if (generatorVersion == null || generatorVersion.isBlank()) {
            throw new IllegalArgumentException("prerequisite.definition.generator-version은 필수다.");
        }
        if (generatorVersion.startsWith("${")) {
            throw new IllegalArgumentException("prerequisite.definition.generator-version이 해석되지 않았습니다. "
                    + "PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT를 설정하세요.");
        }
    }
}
