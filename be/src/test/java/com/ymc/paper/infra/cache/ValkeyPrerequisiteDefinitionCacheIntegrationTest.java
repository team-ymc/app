package com.ymc.paper.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;
import com.ymc.support.IntegrationTest;

class ValkeyPrerequisiteDefinitionCacheIntegrationTest extends IntegrationTest {

    @Autowired
    PrerequisiteDefinitionCache cache;

    @Test
    void 넣은_값을_그대로_돌려주고_TTL이_설정된다() {
        cache.put("prerequisite-definition:v1:d:v:h", new PrerequisiteDefinition("en", "ko"));

        assertThat(cache.get("prerequisite-definition:v1:d:v:h"))
                .contains(new PrerequisiteDefinition("en", "ko"));
        Long ttl = redisTemplate.getExpire("prerequisite-definition:v1:d:v:h");
        assertThat(ttl).isGreaterThan(Duration.ofDays(29).toSeconds());
        assertThat(redisTemplate.opsForValue().get("prerequisite-definition:v1:d:v:h"))
                .contains("\"definitionEn\"").contains("\"generatedAt\"");
    }

    @Test
    void 먼저_저장된_값이_남는다() {
        cache.put("k", new PrerequisiteDefinition("first", "첫째"));
        cache.put("k", new PrerequisiteDefinition("second", "둘째"));

        assertThat(cache.get("k")).contains(new PrerequisiteDefinition("first", "첫째"));
    }

    @Test
    void 없는_키는_empty다() {
        assertThat(cache.get("nope")).isEmpty();
    }

    @Test
    void 깨진_값은_empty다() {
        redisTemplate.opsForValue().set("broken", "not json");
        assertThat(cache.get("broken")).isEmpty();
    }
}
