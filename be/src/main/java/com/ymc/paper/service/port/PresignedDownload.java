package com.ymc.paper.service.port;

import java.time.Instant;

/** 발급된 presigned GET URL과 만료 시각 (계약 `PaperDownload.downloadUrl`·`expiresAt`). */
public record PresignedDownload(String url, Instant expiresAt) {
}
