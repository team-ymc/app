package com.ymc.paper.domain;

/**
 * 논문 라이프사이클. 계약(openapi.yaml `PaperStatus`)의 값과 1:1이다.
 *
 * <p>정상 전이: {@code UPLOAD_PENDING → UPLOADED → PROCESSING → COMPLETED | FAILED}.
 * 상태의 단일 writer는 BE다 — 파싱 서버는 결과만 발행한다.
 *
 * <p>{@link #EXPIRED}는 MVP에서 발생하지 않는다. reconciliation batch(post-MVP, ADR-001 §5)만 쓴다.
 * 계약에 있는 값이라 enum에는 두지만 이 change의 어떤 경로도 이 값으로 전이시키지 않는다.
 */
public enum PaperStatus {

    /** 레코드 생성·presigned URL 발급됨, complete 대기 */
    UPLOAD_PENDING,

    /** complete 수신·S3 HEAD 확인됨 (파싱 발행 직전의 짧은 상태) */
    UPLOADED,

    /** 파싱 진행 중 */
    PROCESSING,

    /** 파싱 완료 (terminal) */
    COMPLETED,

    /** 파싱 실패 (terminal) */
    FAILED,

    /** 업로드가 끝내 확인되지 않음 (terminal). post-MVP */
    EXPIRED;

    /** 헬퍼 : 이 상태가 종착 상태인가 - status.isTerminal() */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED;
    }
}
