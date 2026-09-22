package com.ymc.paper.service.port;

import java.util.Optional;

import com.ymc.paper.service.PrerequisiteDefinition;

/** 선행지식 설명 캐시. 장애는 예외로 올리지 않는다 — get은 empty, put은 무시하고 WARN. */
public interface PrerequisiteDefinitionCache {

    Optional<PrerequisiteDefinition> get(String key);

    void put(String key, PrerequisiteDefinition value);
}
