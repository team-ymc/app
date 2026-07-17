package com.ymc.user.infra.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;
import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;
import com.ymc.user.service.AuthService;
import com.ymc.user.service.OAuthUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Google 인증 성공 → 사용자 upsert → refresh 쿠키 → FE 브릿지 페이지로 302.
 * access token은 여기서 주지 않는다 — FE가 /api/auth/refresh로 받아 메모리에만 둔다 (design §3).
 */
@Component
@RequiredArgsConstructor
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthUserService oAuthUserService;
    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final AuthProperties props;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String providerId = principal.getName();   // google user-name-attribute = sub
        String email = principal.getAttribute("email");
        String displayName = principal.getAttribute("name");

        User user = oAuthUserService.upsert(AuthProvider.GOOGLE, providerId, email, displayName);
        AuthService.IssuedTokens tokens = authService.issueTokens(user.getId());

        response.addHeader(HttpHeaders.SET_COOKIE,
                refreshTokenCookie.issue(tokens.rawRefreshToken()).toString());
        response.sendRedirect(props.feOrigin() + "/auth/popup-done.html");
    }
}
