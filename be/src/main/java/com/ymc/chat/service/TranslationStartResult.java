package com.ymc.chat.service;

import java.util.UUID;

/**
 * 시작 후 relay·SSE event 구성에 필요한 식별자. aiPaperId는 AI 패키지 식별자(Document.requestPaperId)다.
 * trial이면 translation_run 행과 사용량 예약이 없다 — 종결 시 DB 전이를 건너뛴다.
 */
public record TranslationStartResult(UUID paperId, String aiPaperId, UUID translationId, boolean trial) {
}
