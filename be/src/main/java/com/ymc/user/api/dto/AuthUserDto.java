package com.ymc.user.api.dto;

import java.util.UUID;

import com.ymc.user.domain.User;

/** 계약 AuthUser 스키마. */
public record AuthUserDto(UUID userId, String email, String displayName) {

    public static AuthUserDto from(User user) {
        return new AuthUserDto(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
