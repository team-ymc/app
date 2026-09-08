package com.ymc.chat.service;

import java.util.UUID;

/** 시작 트랜잭션 commit 후 relay·SSE event 구성에 필요한 식별자. aiPaperId는 AI 패키지 식별자(Document.requestPaperId)다. */
public record TranslationStartResult(UUID paperId, String aiPaperId, UUID translationId) {
}
