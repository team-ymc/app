package com.ymc.paper.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.ymc.paper.domain.PaperStatus;

/**
 * 계약 `PaperStatusResponse`. complete·status 두 엔드포인트가 공유한다.
 *
 * <p>paperId를 싣는 이유: 서재는 행마다 폴링하므로 응답이 뒤섞여도 판별할 수 있어야 한다.
 * errorCode는 싣지 않는다 — MVP는 실패 코드를 사용자에게 노출하지 않는다.
 */
public record PaperStatusResponse(UUID paperId, PaperStatus status, Instant updatedAt) {
}
