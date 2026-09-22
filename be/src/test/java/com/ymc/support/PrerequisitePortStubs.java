package com.ymc.support;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;
import com.ymc.paper.service.port.PrerequisiteGenerationLock;

/** 컨텍스트 기동용 최소 스텁. Task 5의 Valkey 구현이 대체한다. */
@TestConfiguration(proxyBeanMethods = false)
public class PrerequisitePortStubs {

    @Bean
    PrerequisiteDefinitionCache prerequisiteDefinitionCache() {
        return new PrerequisiteDefinitionCache() {
            @Override
            public Optional<PrerequisiteDefinition> get(String key) {
                return Optional.empty();
            }

            @Override
            public void put(String key, PrerequisiteDefinition value) {
                // no-op
            }
        };
    }

    @Bean
    PrerequisiteGenerationLock prerequisiteGenerationLock() {
        return new PrerequisiteGenerationLock() {
            @Override
            public Optional<String> tryAcquire(UUID userId) {
                return Optional.of(UUID.randomUUID().toString());
            }

            @Override
            public void release(UUID userId, String token) {
                // no-op
            }
        };
    }
}
