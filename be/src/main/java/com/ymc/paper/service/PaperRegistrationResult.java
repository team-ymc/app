package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import com.ymc.paper.domain.PaperStatus;

/** 등록 결과. 엔티티를 api 레이어로 넘기지 않기 위한 값 (be/CLAUDE.md: 엔티티 직접 노출 금지). */
public record PaperRegistrationResult(
        UUID paperId,
        String fileKey,
        String uploadUrl,
        Instant uploadExpiresAt,
        PaperStatus status,
        Instant createdAt) {
}
