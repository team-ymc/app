package com.ymc.paper.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.service.port.PrerequisiteGenerationLock;
import com.ymc.support.IntegrationTest;

class ValkeyPrerequisiteGenerationLockIntegrationTest extends IntegrationTest {

    @Autowired
    PrerequisiteGenerationLock lock;

    @Test
    void 같은_사용자는_동시에_하나만_잡는다() {
        UUID user = UUID.randomUUID();
        Optional<String> first = lock.tryAcquire(user);
        Optional<String> second = lock.tryAcquire(user);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(lock.tryAcquire(UUID.randomUUID())).isPresent();
    }

    @Test
    void 해제하면_다시_잡을_수_있다() {
        UUID user = UUID.randomUUID();
        String token = lock.tryAcquire(user).orElseThrow();

        lock.release(user, token);

        assertThat(lock.tryAcquire(user)).isPresent();
    }

    @Test
    void 다른_토큰으로는_풀리지_않는다() {
        UUID user = UUID.randomUUID();
        lock.tryAcquire(user).orElseThrow();

        lock.release(user, "stale-token");

        assertThat(lock.tryAcquire(user)).isEmpty();
    }

    @Test
    void 만료는_타임아웃_더하기_5초다() {
        UUID user = UUID.randomUUID();
        lock.tryAcquire(user).orElseThrow();

        Long ttl = redisTemplate.getExpire("prerequisite-definition:lock:" + user);
        assertThat(ttl).isBetween(60L, 65L);
    }
}
