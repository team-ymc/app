package com.ymc.user.api;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.user.api.dto.MeResponse;
import com.ymc.user.api.dto.TokenResponse;
import com.ymc.user.infra.security.RefreshTokenCookie;
import com.ymc.user.service.AuthService;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 계약(openapi.yaml 0.1.2)의 /api/auth. HTTP ↔ DTO 변환만 한다 (be/CLAUDE.md). */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(value = RefreshTokenCookie.NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ErrorCode.AUTH_REFRESH_INVALID, "세션이 없습니다.");
        }
        AuthService.RefreshResult result;
        try {
            result = authService.refresh(refreshToken);
        } catch (ApiException e) {
            // 죽은 쿠키 정리 — 만료·폐기·재사용 401에서 브라우저가 같은 토큰을 최대 14일간
            // 재제출하며 재사용 탐지 로그를 오염시키는 것을 막는다 (최종 리뷰 Important 3).
            // 예외 전 addHeader는 GlobalExceptionHandler 응답에도 유지된다.
            response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.expire().toString());
            throw e;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshTokenCookie.issue(result.tokens().rawRefreshToken()).toString())
                .body(TokenResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.expire().toString())
                .build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return MeResponse.from(authService.getUser(UUID.fromString(jwt.getSubject())));
    }
}
