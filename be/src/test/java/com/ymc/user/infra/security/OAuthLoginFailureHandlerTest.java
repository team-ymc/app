package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import com.ymc.common.config.AuthProperties;

class OAuthLoginFailureHandlerTest {

    @Test
    void 실패_시_error_쿼리를_실어_브릿지로_리다이렉트() throws Exception {
        AuthProperties props = new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                Set.of());
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new AuthenticationException("취소됨") {});

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html?error=oauth_failed");
    }

    @Test
    void 비허용_계정_거부는_not_allowed로_전달한다() throws Exception {
        AuthProperties props = new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                Set.of());
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("not_allowed", "거부", null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html?error=not_allowed");
    }

    @Test
    void 그_외_OAuth2_오류코드는_URL로_새지_않는다() throws Exception {
        AuthProperties props = new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                Set.of());
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token_response", "내부 사정", null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html?error=oauth_failed");
    }
}
