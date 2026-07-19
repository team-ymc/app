package com.ymc.user.infra.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;

import lombok.RequiredArgsConstructor;

/**
 * ymc_refresh 쿠키. Path를 /api/auth로 좁혀 다른 API 요청에는 실리지 않게 한다.
 * SameSite=Lax — 크로스사이트 POST에 쿠키가 실리지 않아 CSRF 방어의 근거다 (FT-001).
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookie {

    public static final String NAME = "ymc_refresh";

    private final AuthProperties props;

    public ResponseCookie issue(String rawToken) {
        return base(rawToken, props.refreshTtl());
    }

    public ResponseCookie expire() {
        return base("", Duration.ZERO);
    }

    private ResponseCookie base(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }
}
