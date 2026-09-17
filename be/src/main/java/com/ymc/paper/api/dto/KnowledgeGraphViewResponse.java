package com.ymc.paper.api.dto;

import java.time.Instant;

import com.ymc.paper.service.port.PresignedDownload;

/** 계약 `KnowledgeGraphView`. */
public record KnowledgeGraphViewResponse(String url, Instant expiresAt) {

    public static KnowledgeGraphViewResponse from(PresignedDownload presigned) {
        return new KnowledgeGraphViewResponse(presigned.url(), presigned.expiresAt());
    }
}
