package com.ymc.user.infra.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 인증 실패·사용자 취소 → 브릿지에 error 쿼리로 전달. 복구 플로우는 out of scope (FT-001). */
@Component
@RequiredArgsConstructor
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    private final AuthProperties props;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        response.sendRedirect(props.feOrigin() + "/auth/popup-done.html?error=oauth_failed");
    }
}
