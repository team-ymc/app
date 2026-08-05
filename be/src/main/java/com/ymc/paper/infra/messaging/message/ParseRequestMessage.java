package com.ymc.paper.infra.messaging.message;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * project-docs/contracts/backend-ai/sqs/messaging.yml 0.2.0 `ParseRequest` 대응.
 * wire 필드는 snake_case다.
 *
 * <p>파일 바이트가 아니라 file_key 참조만 보낸다 — 파싱 서버가 자체 권한으로 S3에서 읽는다.
 * 스키마가 {@code additionalProperties: false}이므로 필드를 임의로 늘리지 않는다.
 */
public record ParseRequestMessage(
        @JsonProperty("paper_id") UUID paperId,
        @JsonProperty("file_key") String fileKey) {
}
