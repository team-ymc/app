package com.ymc.chat.api.dto;

import java.time.Instant;
import java.util.UUID;

/** 계약(openapi.yaml `TranslationSseEvent`)의 event별 data payload. data.type == event 이름. */
public final class TranslationSseEventData {

    private TranslationSseEventData() {
    }

    public record Started(String type, UUID paperId, UUID translationId, String status) {
        public static Started of(UUID paperId, UUID translationId) {
            return new Started("translation.started", paperId, translationId, "GENERATING");
        }
    }

    public record Delta(String type, UUID paperId, UUID translationId, String delta) {
        public static Delta of(UUID paperId, UUID translationId, String delta) {
            return new Delta("translation.delta", paperId, translationId, delta);
        }
    }

    public record Completed(
            String type, UUID paperId, UUID translationId,
            String translation, String contentFormat, String status) {
        public static Completed of(UUID paperId, UUID translationId, String translation) {
            return new Completed("translation.completed", paperId, translationId,
                    translation, "markdown", "COMPLETED");
        }
    }

    public record StreamError(String type, UUID paperId, UUID translationId, String status, Detail error) {
        public record Detail(String code, String message, boolean retryable) {
        }

        public static StreamError of(UUID paperId, UUID translationId,
                String code, String message, boolean retryable) {
            return new StreamError("error", paperId, translationId, "FAILED",
                    new Detail(code, message, retryable));
        }
    }

    public record Heartbeat(String type, UUID paperId, UUID translationId, Instant emittedAt) {
        public static Heartbeat of(UUID paperId, UUID translationId) {
            return new Heartbeat("heartbeat", paperId, translationId, Instant.now());
        }
    }
}
