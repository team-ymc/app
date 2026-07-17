package com.ymc.user.service;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;
import com.ymc.user.domain.UserRepository;

import lombok.RequiredArgsConstructor;

/** OAuth 성공 시 사용자 식별·생성. provider+providerId가 키다 (FT-001 Story 2). */
@Service
@RequiredArgsConstructor
public class OAuthUserService {

    private final UserRepository userRepository;

    /**
     * 의도적으로 트랜잭션을 묶지 않는다 — 각 리포지토리 호출이 독립 트랜잭션으로 돌아야, 유니크 위반으로
     * 실패한 INSERT 트랜잭션이 롤백된 뒤 재조회가 새 트랜잭션에서 승자의 커밋된 행을 읽을 수 있다
     * (PG는 위반 시 트랜잭션 전체가 abort됨). 다단계 불변식이 없어 묶음 트랜잭션의 이득도 없다.
     */
    public User upsert(AuthProvider provider, String providerId, String email, String displayName) {
        return userRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> saveNew(provider, providerId, email, displayName));
    }

    private User saveNew(AuthProvider provider, String providerId, String email, String displayName) {
        try {
            return userRepository.saveAndFlush(
                    User.register(provider, providerId, email, displayName, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            // 동시 첫 로그인 경쟁 — 유니크 제약을 이긴 쪽을 다시 읽는다 (PaperRegistrationService 패턴)
            return userRepository.findByProviderAndProviderId(provider, providerId)
                    .orElseThrow(() -> e);
        }
    }
}
