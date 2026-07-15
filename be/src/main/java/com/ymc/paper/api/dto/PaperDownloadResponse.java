package com.ymc.paper.api.dto;

import java.time.Instant;

import com.ymc.paper.service.port.PresignedDownload;

/** 계약 `PaperDownload`. */
public record PaperDownloadResponse(String downloadUrl, Instant expiresAt) {

    public static PaperDownloadResponse from(PresignedDownload presigned) {
        return new PaperDownloadResponse(presigned.url(), presigned.expiresAt());
    }
}
