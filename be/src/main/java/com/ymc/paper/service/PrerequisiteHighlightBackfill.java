package com.ymc.paper.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;

/**
 * 선행지식 적재 도입 전에 컴파일된 Document를 한 번 채운다. 사이드카가 없는 Document는 0건으로 남고
 * 다음 실행에서 다시 대상이 되지만 결과는 같다. dev에서 한 번 돌린 뒤 제거한다.
 */
@Component
public class PrerequisiteHighlightBackfill {

    private static final Logger log = LoggerFactory.getLogger(PrerequisiteHighlightBackfill.class);

    private final DocumentRepository documentRepository;
    private final DocumentPrerequisiteHighlightIngestService ingestService;
    private final boolean enabled;

    public PrerequisiteHighlightBackfill(
            DocumentRepository documentRepository,
            DocumentPrerequisiteHighlightIngestService ingestService,
            @Value("${prerequisite.backfill.enabled:false}") boolean enabled) {
        this.documentRepository = documentRepository;
        this.ingestService = ingestService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled) {
            run();
        }
    }

    /** @return 하이라이트를 1건 이상 적재한 Document 수 */
    public int run() {
        List<Document> targets = documentRepository.findAllCompiledWithoutPrerequisiteHighlights();
        int filled = 0;
        for (Document document : targets) {
            String manifestKey = "papers/" + document.getRequestPaperId() + "/manifest.json";
            try {
                if (ingestService.ingest(document.getId(), manifestKey) > 0) {
                    filled++;
                }
            } catch (RuntimeException e) {
                log.warn("선행지식 채우기 실패, 다음 Document로: documentId={}", document.getId(), e);
            }
        }
        log.info("선행지식 채우기 완료: 대상={}, 적재={}", targets.size(), filled);
        return filled;
    }
}
