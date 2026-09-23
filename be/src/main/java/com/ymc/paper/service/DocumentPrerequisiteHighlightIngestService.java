package com.ymc.paper.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.DocumentContentBlock;
import com.ymc.paper.domain.DocumentContentBlockRepository;
import com.ymc.paper.domain.DocumentPrerequisiteHighlight;
import com.ymc.paper.domain.DocumentPrerequisiteHighlightRepository;
import com.ymc.paper.service.port.PaperPackageReader;
import com.ymc.paper.service.port.ParsedPrerequisiteHighlight;

import lombok.RequiredArgsConstructor;

/**
 * 컴파일 사이드카의 선행지식 범위를 Document 단위로 적재한다. 적재된 블록의 원문을 잘라 text와 대조하고,
 * 어긋나는 항목만 건너뛴다. 재적재는 기존 행을 지우고 다시 넣는다.
 */
@Service
@RequiredArgsConstructor
public class DocumentPrerequisiteHighlightIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPrerequisiteHighlightIngestService.class);

    private final PaperPackageReader packageReader;
    private final DocumentContentBlockRepository blockRepository;
    private final DocumentPrerequisiteHighlightRepository highlightRepository;

    /** @return 적재한 하이라이트 수. 사이드카가 없거나 형식이 어긋나면 0. */
    @Transactional
    public int ingest(UUID documentId, String manifestKey) {
        List<ParsedPrerequisiteHighlight> parsed = packageReader.readPrerequisiteHighlights(manifestKey);
        Map<String, DocumentContentBlock> byId = blockRepository
                .findAllByDocumentIdOrderByGlobalOrderAsc(documentId).stream()
                .collect(Collectors.toMap(DocumentContentBlock::getBlockId, Function.identity()));

        List<DocumentPrerequisiteHighlight> rows = new ArrayList<>();
        for (ParsedPrerequisiteHighlight h : parsed) {
            DocumentContentBlock block = byId.get(h.blockId());
            if (block == null) {
                log.warn("사이드카에만 있는 blockId, 건너뜀: highlightId={}, blockId={}, documentId={}",
                        h.highlightId(), h.blockId(), documentId);
                continue;
            }
            String text = block.getContent().path("text").asText(null);
            if (text == null || h.endOffset() > text.length()
                    || !text.substring(h.startOffset(), h.endOffset()).equals(h.text())) {
                log.warn("본문 범위와 text가 다른 하이라이트, 건너뜀: highlightId={}, blockId={}, documentId={}",
                        h.highlightId(), h.blockId(), documentId);
                continue;
            }
            rows.add(DocumentPrerequisiteHighlight.of(
                    documentId, h.highlightId(), h.blockId(), h.startOffset(), h.endOffset(), h.text()));
        }
        highlightRepository.deleteByDocumentId(documentId);
        highlightRepository.saveAll(rows);
        return rows.size();
    }
}
