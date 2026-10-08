package com.ymc.paper.service.port;

import java.util.Optional;

import com.ymc.paper.service.PrerequisiteDefinition;

/** 선행지식 설명 캐시. 장애는 예외로 올리지 않는다 — get은 empty, put은 무시하고 WARN. */
public interface PrerequisiteDefinitionCache {

    Optional<PrerequisiteDefinition> get(String key);

    void put(String key, PrerequisiteDefinition value);

    /** 만료를 없앤다. 체험 논문의 설명은 미리 만들어 두고 TTL 없이 유지한다. 실패는 WARN만 남긴다. */
    void keepForever(String key);
}
