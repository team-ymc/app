package com.ymc.paper.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.PaperRegistrationResult;

/** 계약 `PaperCreated`. */
public record PaperCreated(
        UUID paperId,
        String fileKey,
        Map<String, String> uploadHeaders,
        String uploadUrl,
        Instant uploadExpiresAt,
        PaperStatus status,
        Instant createdAt) {

    public static PaperCreated from(PaperRegistrationResult result) {
        return new PaperCreated(
                result.paperId(),
                result.fileKey(),
                result.uploadHeaders(),
                result.uploadUrl(),
                result.uploadExpiresAt(),
                result.status(),
                result.createdAt());
    }
}
