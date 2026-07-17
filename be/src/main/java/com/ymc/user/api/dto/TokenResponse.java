package com.ymc.user.api.dto;

import com.ymc.user.service.AuthService;

/** 계약 TokenResponse 스키마 — POST /api/auth/refresh 응답. */
public record TokenResponse(String accessToken, long expiresIn, AuthUserDto user) {

    public static TokenResponse from(AuthService.RefreshResult result) {
        return new TokenResponse(
                result.tokens().accessToken(),
                result.tokens().expiresInSeconds(),
                AuthUserDto.from(result.user()));
    }
}
