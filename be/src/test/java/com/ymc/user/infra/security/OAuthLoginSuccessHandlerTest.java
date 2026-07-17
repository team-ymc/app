package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.ymc.common.config.AuthProperties;
import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;
import com.ymc.user.service.AuthService;
import com.ymc.user.service.OAuthUserService;

@ExtendWith(MockitoExtension.class)
class OAuthLoginSuccessHandlerTest {

    @Mock
    private OAuthUserService oAuthUserService;

    @Mock
    private AuthService authService;

    // AuthProperties는 record(final)라 Mockito 대상이 아님 — 실객체로 조립한다.
    private final AuthProperties props = new AuthProperties(
            "test-secret-key-that-is-32-bytes-long!!",
            Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false);

    @Test
    void 성공_시_upsert_쿠키_브릿지_리다이렉트() throws Exception {
        OAuthLoginSuccessHandler handler = new OAuthLoginSuccessHandler(
                oAuthUserService, authService, new RefreshTokenCookie(props), props);
        User user = User.register(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동", Instant.now());
        when(oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동")).thenReturn(user);
        when(authService.issueTokens(any(UUID.class)))
                .thenReturn(new AuthService.IssuedTokens("access", 1800L, "raw-refresh"));

        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", "sub-1", "email", "a@b.c", "name", "홍길동"),
                "sub");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new TestingAuthenticationToken(principal, null));

        verify(oAuthUserService).upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        assertThat(response.getHeader("Set-Cookie")).contains("ymc_refresh=raw-refresh");
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html");
    }
}
