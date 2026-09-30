package com.ymc.paper.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.ymc.paper.domain.KnowledgeGraphStatus;
import com.ymc.paper.domain.PaperFailReason;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.domain.TranslationStatus;

/**
 * 계약 `PaperStatusResponse`. complete·status·retry 세 엔드포인트가 공유한다.
 *
 * <p>paperId를 싣는 이유: 서재는 행마다 폴링하므로 응답이 뒤섞여도 판별할 수 있어야 한다.
 * 내부 실패 코드는 싣지 않는다. failReason은 재시도 가능 여부만 알린다.
 */
public record PaperStatusResponse(
        UUID paperId, PaperStatus status, TranslationStatus translationStatus,
        KnowledgeGraphStatus knowledgeGraphStatus, Instant updatedAt,
        PaperFailReason failReason, boolean compileRetryable) {
}
