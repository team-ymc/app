package com.ymc.user.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.config.AuthProperties;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.user.domain.RefreshToken;
import com.ymc.user.domain.RefreshTokenRepository;
import com.ymc.user.domain.User;
import com.ymc.user.domain.UserRepository;
import com.ymc.user.infra.security.JwtTokenProvider;
import com.ymc.user.infra.security.TokenHasher;

import lombok.RequiredArgsConstructor;

/** 세션(토큰 쌍) 발급·회전·폐기. 회전·재사용 탐지 규칙은 design §5. */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthProperties props;

    public record IssuedTokens(String accessToken, long expiresInSeconds, String rawRefreshToken) {
    }

    public record RefreshResult(IssuedTokens tokens, User user) {
    }

    @Transactional
    public IssuedTokens issueTokens(UUID userId) {
        Instant now = Instant.now();
        String raw = generateRefreshToken();
        refreshTokenRepository.save(
                RefreshToken.issue(userId, TokenHasher.sha256(raw), now, props.refreshTtl()));
        return new IssuedTokens(
                jwtTokenProvider.issueAccessToken(userId, now),
                jwtTokenProvider.accessTtlSeconds(),
                raw);
    }

    // 재사용 탐지 시 revokeAllActiveByUserId(전체 세션 폐기)를 실행한 뒤 401을 던진다.
    // ApiException은 unchecked라 기본 규칙대로면 던지는 순간 트랜잭션이 롤백돼
    // 방금 실행한 폐기까지 함께 취소된다 — 그러면 재사용 탐지가 아무 효과가 없다.
    // noRollbackFor로 그 폐기가 커밋되게 한다.
    @Transactional(noRollbackFor = ApiException.class)
    public RefreshResult refresh(String rawToken) {
        Instant now = Instant.now();
        RefreshToken current = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(AuthService::invalidRefresh);

        if (current.isRevoked()) {
            // 회전으로 폐기된 토큰의 재사용 = 탈취 신호. 응답은 일반 401과 동일 — 감지 사실 비노출.
            int closed = refreshTokenRepository.revokeAllActiveByUserId(current.getUserId(), now);
            log.warn("폐기된 refresh token 재사용 감지 — userId={} 활성 세션 {}건 폐기",
                    current.getUserId(), closed);
            throw invalidRefresh();
        }
        if (current.isExpired(now)) {
            throw invalidRefresh();
        }

        current.revoke(now);
        User user = userRepository.findById(current.getUserId())
                .orElseThrow(AuthService::invalidRefresh);
        // 같은 트랜잭션 안의 직접 호출 — issueTokens의 @Transactional은 REQUIRED라 의미가 같다.
        return new RefreshResult(issueTokens(user.getId()), user);
    }

    /** 멱등 — 쿠키가 없거나 이미 폐기된 토큰이어도 조용히 성공한다 (계약: 항상 204). */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .ifPresent(token -> token.revoke(Instant.now()));
    }

    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "알 수 없는 사용자입니다."));
    }

    private static String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException invalidRefresh() {
        return new ApiException(ErrorCode.AUTH_REFRESH_INVALID, "유효하지 않은 세션입니다.");
    }
}
