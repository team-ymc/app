package com.ymc.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void register는_id를_생성하고_필드를_채운다() {
        User user = User.register(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동", Instant.now());
        assertThat(user.getId()).isNotNull();
        assertThat(user.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(user.getProviderId()).isEqualTo("sub-1");
    }

    @Test
    void email은_null_허용_providerId는_필수() {
        assertThat(User.register(AuthProvider.GOOGLE, "sub-1", null, "이름", Instant.now())
                .getEmail()).isNull();
        assertThatNullPointerException()
                .isThrownBy(() -> User.register(AuthProvider.GOOGLE, null, null, "이름", Instant.now()));
    }
}
