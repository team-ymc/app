package com.ymc.chat.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 계약(openapi.yaml `ChatSseEvent`)의 event별 data payload. record 이름이 아니라
 * {@code type} 필드와 SSE event line이 계약의 event 이름이다 (data.type == event 이름 규칙).
 */
public final class ChatSseEventData {

    private ChatSseEventData() {
    }

    /** event: message.started — commit 후 식별자 확정 통지. */
    public record Started(
            String type, UUID paperId, UUID sessionId, UUID messageId,
            UUID clientMessageId, String status) {

        public static Started of(UUID paperId, UUID sessionId, UUID messageId, UUID clientMessageId) {
            return new Started("message.started", paperId, sessionId, messageId,
                    clientMessageId, "GENERATING");
        }
    }

    /** event: message.delta — 부분 문자열 조각. */
    public record Delta(String type, UUID paperId, UUID sessionId, UUID messageId, String delta) {

        public static Delta of(UUID paperId, UUID sessionId, UUID messageId, String delta) {
            return new Delta("message.delta", paperId, sessionId, messageId, delta);
        }
    }

    /** event: message.completed — COMPLETED commit 후의 성공 terminal. */
    public record Completed(
            String type, UUID paperId, UUID sessionId, UUID messageId,
            String content, String contentFormat, String status) {

        public static Completed of(UUID paperId, UUID sessionId, UUID messageId, String content) {
            return new Completed("message.completed", paperId, sessionId, messageId,
                    content, "markdown", "COMPLETED");
        }
    }

    /** event: error — FAILED commit 후의 실패 terminal. */
    public record StreamError(
            String type, UUID paperId, UUID sessionId, UUID messageId,
            String status, Detail error) {

        public record Detail(String code, String message, boolean retryable) {
        }

        public static StreamError of(
                UUID paperId, UUID sessionId, UUID messageId,
                String code, String message, boolean retryable) {
            return new StreamError("error", paperId, sessionId, messageId, "FAILED",
                    new Detail(code, message, retryable));
        }
    }

    /** event: heartbeat — 침묵 구간 연결 유지용. 상태를 바꾸지 않는다 (계약). */
    public record Heartbeat(String type, UUID paperId, UUID sessionId, UUID messageId, Instant emittedAt) {

        public static Heartbeat of(UUID paperId, UUID sessionId, UUID messageId) {
            return new Heartbeat("heartbeat", paperId, sessionId, messageId, Instant.now());
        }
    }
}
