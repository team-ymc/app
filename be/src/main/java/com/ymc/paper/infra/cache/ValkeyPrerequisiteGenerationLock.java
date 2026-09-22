package com.ymc.paper.infra.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ymc.paper.infra.ai.PrerequisiteDefinitionProperties;
import com.ymc.paper.service.port.PrerequisiteGenerationLock;

/** 사용자당 잠금 1개를 SET NX PX로 잡고, 토큰이 같을 때만 Lua로 지운다. */
@Component
public class ValkeyPrerequisiteGenerationLock implements PrerequisiteGenerationLock {

    private static final Logger log = LoggerFactory.getLogger(ValkeyPrerequisiteGenerationLock.class);
    private static final Duration GRACE = Duration.ofSeconds(5);
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration expiry;

    public ValkeyPrerequisiteGenerationLock(StringRedisTemplate redis, PrerequisiteDefinitionProperties properties) {
        this.redis = redis;
        this.expiry = properties.timeout().plus(GRACE);
    }

    static String key(UUID userId) {
        return "prerequisite-definition:lock:" + userId;
    }

    @Override
    public Optional<String> tryAcquire(UUID userId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key(userId), token, expiry);
        } catch (RuntimeException e) {
            throw new LockUnavailableException("선행지식 잠금 저장소에 접근할 수 없습니다.", e);
        }
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    @Override
    public void release(UUID userId, String token) {
        try {
            redis.execute(RELEASE_IF_OWNER, List.of(key(userId)), token);
        } catch (RuntimeException e) {
            log.warn("선행지식 잠금 해제 실패, 만료로 풀린다: userId={}", userId, e);
        }
    }
}
