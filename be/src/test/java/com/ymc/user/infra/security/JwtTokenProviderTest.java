package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.ymc.common.config.AuthProperties;

class JwtTokenProviderTest {

    private final AuthProperties props = new AuthProperties(
            "test-secret-key-that-is-32-bytes-long!!",
            Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false);

    private final JwtTokenProvider provider = new JwtTokenProvider(props);

    @Test
    void 발급한_토큰은_같은_시크릿의_디코더로_검증되고_sub와_만료가_맞다() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        String token = provider.issueAccessToken(userId, now);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(
                new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")).build();
        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void accessTtlSeconds는_설정값을_초로_돌려준다() {
        assertThat(provider.accessTtlSeconds()).isEqualTo(1800L);
    }
}
