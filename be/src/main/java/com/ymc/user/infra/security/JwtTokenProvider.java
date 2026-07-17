package com.ymc.user.infra.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ymc.common.config.AuthProperties;

/**
 * access JWT 발급 (HS256 대칭키). 검증은 resource server의 {@code JwtDecoder}가 같은 키로 한다.
 * access는 발급 후 회수 불가 — 만료가 곧 폐기다 (design §3).
 */
@Component
public class JwtTokenProvider {

    private final JwtEncoder encoder;
    private final Duration accessTtl;

    public JwtTokenProvider(AuthProperties props) {
        SecretKey key = new SecretKeySpec(
                props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessTtl = props.accessTtl();
    }

    public String issueAccessToken(UUID userId, Instant now) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
