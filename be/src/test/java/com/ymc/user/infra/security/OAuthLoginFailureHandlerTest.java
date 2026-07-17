package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;

import com.ymc.common.config.AuthProperties;

class OAuthLoginFailureHandlerTest {

    @Test
    void 실패_시_error_쿼리를_실어_브릿지로_리다이렉트() throws Exception {
        AuthProperties props = new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false);
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new AuthenticationException("취소됨") {});

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html?error=oauth_failed");
    }
}
