package com.ymc.paper.infra.messaging.message;

import java.util.UUID;

/**
 * contracts/schema/parse-request.schema.json 대응.
 *
 * <p>파일 바이트가 아니라 fileKey 참조만 보낸다 — 파싱 서버가 자체 권한으로 S3에서 읽는다 (ADR-001).
 * 스키마가 {@code additionalProperties: false}이므로 필드를 임의로 늘리지 않는다.
 */
public record ParseRequestMessage(UUID paperId, String fileKey) {
}
