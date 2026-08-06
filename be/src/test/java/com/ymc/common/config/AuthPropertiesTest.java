package com.ymc.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AuthPropertiesTest {

    private static AuthProperties withWhitelist(Set<String> whitelist) {
        return new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                whitelist);
    }

    @Test
    void 명단이_null이면_빈_집합이다() {
        assertThat(withWhitelist(null).loginWhitelist()).isEmpty();
    }

    @Test
    void 명단은_소문자로_정규화되고_앞뒤_공백이_제거된다() {
        AuthProperties props = withWhitelist(Set.copyOf(List.of("  A@Example.COM ", "b@x.com")));
        assertThat(props.loginWhitelist()).containsExactlyInAnyOrder("a@example.com", "b@x.com");
    }

    @Test
    void 빈_문자열_항목은_버린다() {
        AuthProperties props = withWhitelist(Set.copyOf(List.of("a@x.com", "   ")));
        assertThat(props.loginWhitelist()).containsExactly("a@x.com");
    }

    @Test
    void 미해석_플레이스홀더는_기동_실패다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withWhitelist(Set.of("${LOGIN_WHITELIST}")));
    }

    @Test
    void 골뱅이_없는_항목은_기동_실패다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withWhitelist(Set.of("not-an-email")));
    }
}
