package com.ymc.user.service;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;

import lombok.RequiredArgsConstructor;

/**
 * 로그인 허용 여부 판정. 별도 회원가입 플로우가 없어 가입 = 첫 로그인이므로,
 * 여기서 막으면 신규 가입과 기존 계정 재로그인이 함께 막힌다.
 */
@Component
@RequiredArgsConstructor
public class LoginWhitelist {

    private final AuthProperties props;

    /** 목록이 비면 게이트가 꺼진 것으로 본다 — 설정을 넣지 않은 환경의 기본 동작이다. */
    public boolean isAllowed(String email) {
        Set<String> allowed = props.loginWhitelist();
        return allowed.isEmpty()
                || (email != null && allowed.contains(email.trim().toLowerCase(Locale.ROOT)));
    }
}
