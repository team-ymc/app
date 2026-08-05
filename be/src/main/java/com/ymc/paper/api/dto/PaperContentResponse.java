package com.ymc.paper.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.service.PaperContentView;

/** 계약 `PaperContentResponse`. */
public record PaperContentResponse(
        UUID paperId,
        String title,
        int schemaVersion,
        List<Block> blocks,
        Map<String, Asset> assets) {

    public static PaperContentResponse from(PaperContentView view) {
        return new PaperContentResponse(
                view.paperId(),
                view.title(),
                view.schemaVersion(),
                view.blocks().stream()
                        .map(b -> new Block(b.blockId(), b.globalOrder(), b.label(),
                                b.headingLevel(), b.sectionPath(), b.content()))
                        .toList(),
                view.assets().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> new Asset(e.getValue().url(), e.getValue().mediaType(),
                                        e.getValue().expiresAt()),
                                (a, b) -> a,
                                java.util.LinkedHashMap::new)));
    }

    /** 계약 `PaperContentBlock`. content는 적재 시 계약형으로 저장돼 있어 그대로 내보낸다. */
    public record Block(
            String blockId,
            int globalOrder,
            String label,
            Integer headingLevel,
            List<String> sectionPath,
            JsonNode content) {
    }

    /** 계약 `PaperContentAsset`. */
    public record Asset(String url, String mediaType, Instant expiresAt) {
    }
}
