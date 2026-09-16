package com.ymc.paper.service;

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
import com.ymc.paper.service.port.PaperPackageReader;

import lombok.RequiredArgsConstructor;

/**
 * 컴파일 산출물 사이드카의 번역을 적재된 블록에 병합한다. translated 블록만 골라 해당 행의 content에
 * textKor를 더한다 — 원문·이미지·표 행은 손대지 않는다. 같은 값을 다시 병합해도 결과가 같아 재전달에 안전하다.
 */
@Service
@RequiredArgsConstructor
public class DocumentTranslationMergeService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTranslationMergeService.class);

    private final PaperPackageReader packageReader;
    private final DocumentContentBlockRepository blockRepository;

    /** @return 병합한 블록 수. 사이드카가 없거나 형식이 어긋나면 0. */
    @Transactional
    public int merge(UUID documentId, String manifestKey) {
        Map<String, String> translations = packageReader.readTranslations(manifestKey);
        if (translations.isEmpty()) {
            return 0;
        }
        Map<String, DocumentContentBlock> byId = blockRepository
                .findAllByDocumentIdOrderByGlobalOrderAsc(documentId).stream()
                .collect(Collectors.toMap(DocumentContentBlock::getBlockId, Function.identity()));

        int merged = 0;
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            DocumentContentBlock block = byId.get(entry.getKey());
            if (block == null) {
                log.warn("사이드카에만 있는 blockId, 건너뜀: blockId={}, documentId={}", entry.getKey(), documentId);
                continue;
            }
            if (!block.mergeTranslation(entry.getValue())) {
                log.warn("텍스트가 아닌 블록에 온 번역, 건너뜀: blockId={}, documentId={}", entry.getKey(), documentId);
                continue;
            }
            merged++;
        }
        return merged;
    }
}
