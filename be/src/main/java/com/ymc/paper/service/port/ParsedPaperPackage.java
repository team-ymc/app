package com.ymc.paper.service.port;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 완전히 해석된 파서 패키지 — 수식 tex·표 html은 이미 인라인돼 있고, content는 계약
 * (openapi.yaml PaperContentBlock.content) 형태다. 적재 서비스는 이걸 그대로 저장만 한다.
 */
public record ParsedPaperPackage(
        String title,
        int schemaVersion,
        List<Block> blocks,
        List<Asset> assets) {

    public record Block(
            String blockId,
            int globalOrder,
            String label,
            Integer headingLevel,
            List<String> sectionPath,
            JsonNode content) {
    }

    /** 이미지·차트만 — tex·html은 블록에 인라인되므로 여기 없다 (계약 assets 주석). */
    public record Asset(String assetKey, String s3Key, String mediaType) {
    }
}
