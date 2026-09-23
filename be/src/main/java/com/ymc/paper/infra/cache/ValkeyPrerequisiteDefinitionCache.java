package com.ymc.paper.infra.cache;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.infra.ai.PrerequisiteDefinitionProperties;
import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.PrerequisiteDefinitionMetrics;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;

import lombok.RequiredArgsConstructor;

/** 설명을 SET NX + 고정 TTL로 저장한다. 먼저 저장된 값이 남고 조회는 TTL을 늘리지 않는다. */
@Component
@RequiredArgsConstructor
public class ValkeyPrerequisiteDefinitionCache implements PrerequisiteDefinitionCache {

    private static final Logger log = LoggerFactory.getLogger(ValkeyPrerequisiteDefinitionCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PrerequisiteDefinitionProperties properties;
    private final PrerequisiteDefinitionMetrics metrics;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Value(String definitionEn, String definitionKo, Instant generatedAt) {
    }

    @Override
    public Optional<PrerequisiteDefinition> get(String key) {
        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            metrics.cacheError();
            log.warn("선행지식 캐시 조회 실패, MISS로 진행: key={}", key, e);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            Value v = objectMapper.readValue(json, Value.class);
            if (v.definitionEn() == null || v.definitionKo() == null) {
                return Optional.empty();
            }
            return Optional.of(new PrerequisiteDefinition(v.definitionEn(), v.definitionKo()));
        } catch (JacksonException e) {
            log.warn("선행지식 캐시 값 역직렬화 실패, MISS로 진행: key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, PrerequisiteDefinition value) {
        try {
            String json = objectMapper.writeValueAsString(
                    new Value(value.definitionEn(), value.definitionKo(), Instant.now()));
            redis.opsForValue().setIfAbsent(key, json, properties.cacheTtl());
        } catch (RuntimeException | JacksonException e) {
            metrics.cacheError();
            log.warn("선행지식 캐시 저장 실패, 생성 결과는 그대로 반환: key={}", key, e);
        }
    }
}
