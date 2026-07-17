package com.ymc.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private final Instant now = Instant.parse("2026-07-17T00:00:00Z");

    @Test
    void issue는_만료를_now_plus_ttl로_계산하고_미폐기_상태다() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), "hash", now, Duration.ofDays(14));
        assertThat(token.getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(14)));
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.isExpired(now)).isFalse();
    }

    @Test
    void revoke_후_isRevoked_true_재호출은_최초_시각_유지() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), "hash", now, Duration.ofDays(14));
        token.revoke(now.plusSeconds(60));
        token.revoke(now.plusSeconds(120));
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.getRevokedAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void 만료_경계_지나면_isExpired_true() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), "hash", now, Duration.ofDays(14));
        assertThat(token.isExpired(now.plus(Duration.ofDays(14)).plusSeconds(1))).isTrue();
    }
}
