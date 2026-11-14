package com.ymc.paper.service.port;

import java.util.UUID;

/**
 * 파싱 요청 발행(BE → AI) 포트. 조건부 전이에 성공한 호출만 발행한다 — 중복 complete는 재발행하지 않는다
 * (project-docs/contracts/backend-ai/messaging.yml `parse-requests` 채널).
 */
public interface ParseRequestPublisher {

    /** 수락 여부가 불확실한 실패는 {@link AcceptanceUnknownException}으로 전달한다. */
    void publish(UUID paperId, String fileKey);

    final class AcceptanceUnknownException extends RuntimeException {
        public AcceptanceUnknownException(Throwable cause) {
            super("SQS 파싱 요청 수락 여부를 확인할 수 없습니다.", cause);
        }
    }
}
