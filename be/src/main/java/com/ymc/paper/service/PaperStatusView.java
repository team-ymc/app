package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.domain.TranslationStatus;

/** 상태 응답의 재료. 엔티티를 api 레이어로 넘기지 않기 위한 값 (be/CLAUDE.md). */
public record PaperStatusView(UUID paperId, PaperStatus status, TranslationStatus translationStatus, Instant updatedAt) {
}
