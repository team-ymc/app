package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import com.ymc.paper.domain.PaperFailReason;
import com.ymc.paper.domain.PaperStatus;

/** 목록 응답의 재료. 엔티티를 api로 넘기지 않기 위한 값 (be/CLAUDE.md). failReason은 FAILED가 아니면 null. */
public record PaperListView(UUID paperId, String title, String filename, PaperStatus status,
                            Instant createdAt, Instant updatedAt, Instant lastAccessedAt,
                            PaperFailReason failReason) {
}
