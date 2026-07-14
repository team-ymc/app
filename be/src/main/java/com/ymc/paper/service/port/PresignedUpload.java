package com.ymc.paper.service.port;

import java.time.Instant;

/** 발급된 presigned PUT URL과 그 만료 시각 (계약 `PaperCreated.uploadUrl`·`uploadExpiresAt`). */
public record PresignedUpload(String url, Instant expiresAt) {
}
