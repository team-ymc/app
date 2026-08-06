package com.ymc.user.infra.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
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
        // 우리 코드만 통과시킨다. 스프링이 만든 오류 코드는 내부 사정을 담을 수 있어 URL로 흘리지 않는다.
        String code = exception instanceof OAuth2AuthenticationException oauthFailure
                && WhitelistedOidcUserService.NOT_ALLOWED_CODE.equals(
                        oauthFailure.getError().getErrorCode())
                ? WhitelistedOidcUserService.NOT_ALLOWED_CODE
                : "oauth_failed";
        response.sendRedirect(props.feOrigin() + "/auth/popup-done.html?error=" + code);
    }
}
