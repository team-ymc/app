package com.ymc.user.service;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;
import com.ymc.user.domain.UserRepository;

import lombok.RequiredArgsConstructor;

/** OAuth 성공 시 사용자 식별·생성. provider+providerId가 키다 (FT-001 Story 2). */
@Service
@RequiredArgsConstructor
public class OAuthUserService {

    private final UserRepository userRepository;

    @Transactional
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
