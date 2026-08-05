package com.ymc.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** 계약 PaperContentResponse에 대응하는 서비스 뷰. 트랜잭션 안에서 완성한다 (OSIV off). */
public record PaperContentView(
        UUID paperId,
        String title,
        int schemaVersion,
        List<Block> blocks,
        Map<String, Asset> assets) {

    public record Block(
            String blockId,
            int globalOrder,
            String label,
            Integer headingLevel,
            List<String> sectionPath,
            JsonNode content) {
    }

    public record Asset(String url, String mediaType, Instant expiresAt) {
    }
}
