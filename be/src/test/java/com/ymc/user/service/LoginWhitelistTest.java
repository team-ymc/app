package com.ymc.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ymc.common.config.AuthProperties;

class LoginWhitelistTest {

    private static LoginWhitelist with(Set<String> whitelist) {
        return new LoginWhitelist(new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                whitelist));
    }

    @Test
    void 명단이_비면_모두_허용된다() {
        LoginWhitelist whitelist = with(Set.of());
        assertThat(whitelist.isAllowed("아무나@x.com")).isTrue();
        assertThat(whitelist.isAllowed(null)).isTrue();
    }

    @Test
    void 명단에_있으면_허용된다() {
        assertThat(with(Set.of("a@x.com")).isAllowed("a@x.com")).isTrue();
    }

    @Test
    void 명단에_없으면_거부된다() {
        assertThat(with(Set.of("a@x.com")).isAllowed("b@x.com")).isFalse();
    }

    @Test
    void 대소문자와_앞뒤_공백을_무시한다() {
        LoginWhitelist whitelist = with(Set.of("a@x.com"));
        assertThat(whitelist.isAllowed("  A@X.CoM ")).isTrue();
    }

    @Test
    void 명단이_있는데_email이_null이면_거부된다() {
        assertThat(with(Set.of("a@x.com")).isAllowed(null)).isFalse();
    }
}
