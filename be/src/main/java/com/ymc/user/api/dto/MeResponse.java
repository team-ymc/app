package com.ymc.user.api.dto;

import java.util.UUID;

import com.ymc.user.domain.User;

/** 계약 AuthUser 스키마 — GET /api/auth/me 응답. */
public record MeResponse(UUID userId, String email, String displayName) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
