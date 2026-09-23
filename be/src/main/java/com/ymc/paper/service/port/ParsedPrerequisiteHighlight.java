package com.ymc.paper.service.port;

/** 컴파일 사이드카의 선행지식 범위 1개. offset은 UTF-16 code unit, start 포함·end 제외. */
public record ParsedPrerequisiteHighlight(
        String highlightId, String blockId, int startOffset, int endOffset, String text) {
}
