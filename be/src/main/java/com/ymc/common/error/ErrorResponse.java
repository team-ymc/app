package com.ymc.common.error;

/** 계약의 `Error` 스키마 — code + message. */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message);
    }
}
