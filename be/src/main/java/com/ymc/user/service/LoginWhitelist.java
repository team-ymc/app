package com.ymc.user.service;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;

import lombok.RequiredArgsConstructor;

/** 가입 = 첫 로그인이라, 여기서 막으면 신규 가입과 기존 계정 재로그인이 함께 막힌다. */
@Component
@RequiredArgsConstructor
public class LoginWhitelist {

    private final AuthProperties props;

    /** 목록이 비면 게이트 off — 설정을 안 넣은 환경의 기본 동작이다. */
    public boolean isAllowed(String email) {
        Set<String> allowed = props.loginWhitelist();
        return allowed.isEmpty()
                || (email != null && allowed.contains(email.trim().toLowerCase(Locale.ROOT)));
    }
}
