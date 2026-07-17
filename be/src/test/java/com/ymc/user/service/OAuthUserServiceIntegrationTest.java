package com.ymc.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.support.IntegrationTest;
import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;

class OAuthUserServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private OAuthUserService oAuthUserService;

    @Test
    @DisplayName("신규 provider+providerId → 사용자 생성 (FT-001 Story 2)")
    void 신규면_생성한다() {
        User user = oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        assertThat(userRepository.findById(user.getId())).isPresent();
        assertThat(user.getEmail()).isEqualTo("a@b.c");
    }

    @Test
    @DisplayName("같은 provider+providerId 재로그인 → 같은 사용자 (레코드 1개)")
    void 기존이면_같은_사용자를_돌려준다() {
        User first = oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        User second = oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
