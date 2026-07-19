package com.ymc.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.support.IntegrationTest;
import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.RefreshToken;
import com.ymc.user.domain.User;
import com.ymc.user.infra.security.TokenHasher;
import com.ymc.user.service.AuthService.IssuedTokens;
import com.ymc.user.service.AuthService.RefreshResult;

class AuthServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private AuthService authService;

    private UUID userId;

    @BeforeEach
    void seedUser() {
        User user = userRepository.save(
                User.register(AuthProvider.GOOGLE, "sub-auth", "a@b.c", "홍길동", Instant.now()));
        userId = user.getId();
    }

    @Test
    @DisplayName("refresh 성공: 새 토큰 발급 + 이전 토큰은 폐기(회전)")
    void 회전한다() {
        IssuedTokens issued = authService.issueTokens(userId);

        RefreshResult result = authService.refresh(issued.rawRefreshToken());

        assertThat(result.tokens().rawRefreshToken()).isNotEqualTo(issued.rawRefreshToken());
        assertThat(result.user().getId()).isEqualTo(userId);
        RefreshToken old = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256(issued.rawRefreshToken())).orElseThrow();
        assertThat(old.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("폐기된 토큰 재사용(탈취 신호) → 401 + 사용자 전체 세션 폐기")
    void 재사용은_전체_세션을_폐기한다() {
        IssuedTokens first = authService.issueTokens(userId);
        RefreshResult rotated = authService.refresh(first.rawRefreshToken());

        assertThatThrownBy(() -> authService.refresh(first.rawRefreshToken()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AUTH_REFRESH_INVALID);

        // 회전으로 살아 있던 최신 토큰까지 전부 폐기됐다
        assertThatThrownBy(() -> authService.refresh(rotated.tokens().rawRefreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("만료된 토큰 → 401")
    void 만료는_401() {
        String raw = "expired-raw-token";
        refreshTokenRepository.save(RefreshToken.issue(
                userId, TokenHasher.sha256(raw),
                Instant.now().minus(Duration.ofDays(15)), Duration.ofDays(14)));

        assertThatThrownBy(() -> authService.refresh(raw))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AUTH_REFRESH_INVALID);
    }

    @Test
    @DisplayName("logout: 토큰 폐기 → 이후 refresh 불가. 없는 토큰·null도 예외 없음(멱등)")
    void 로그아웃은_멱등이다() {
        IssuedTokens issued = authService.issueTokens(userId);
        authService.logout(issued.rawRefreshToken());
        authService.logout(issued.rawRefreshToken());
        authService.logout(null);

        assertThatThrownBy(() -> authService.refresh(issued.rawRefreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("알 수 없는 토큰 → 401")
    void 모르는_토큰은_401() {
        assertThatThrownBy(() -> authService.refresh("unknown-token"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AUTH_REFRESH_INVALID);
    }
}
