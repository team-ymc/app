package com.ymc.paper.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 결과 반영 — 메시지의 paper_id(= Document의 requestPaperId)로 Document를 역조회해
 * 전이·적재한다. 대표 Paper가 삭제돼도 이 역조회는 성립한다.
 */
@Service
@RequiredArgsConstructor
public class ParseResultService {

    private static final Logger log = LoggerFactory.getLogger(ParseResultService.class);

    private final DocumentTransitions transitions;
    private final DocumentRepository documentRepository;
    private final DocumentContentIngestService ingestService;

    public void apply(UUID requestPaperId, DocumentStatus terminal, String errorCode,
            String manifestKey) {
        Optional<Document> found = documentRepository.findByRequestPaperId(requestPaperId);
        if (found.isEmpty()) {
            log.warn("파싱 결과 미반영, 대응 document 없음: requestPaperId={}", requestPaperId);
            return;
        }
        UUID documentId = found.get().getId();

        boolean transitioned = transitions.markParsedAndSettle(documentId, terminal, errorCode);
        if (transitioned) {
            log.info("파싱 결과 반영: requestPaperId={}, documentId={}, status={}",
                    requestPaperId, documentId, terminal);
        } else {
            log.warn("파싱 결과 미반영, 이미 terminal: requestPaperId={}, documentId={}, status={}",
                    requestPaperId, documentId, terminal);
        }

        if (terminal != DocumentStatus.COMPLETED || manifestKey == null) {
            return;
        }
        boolean completed = transitioned || documentRepository.findById(documentId)
                .map(d -> d.getStatus() == DocumentStatus.COMPLETED)
                .orElse(false);
        if (completed && !ingestService.isIngested(documentId)) {
            ingestService.ingest(documentId, manifestKey);
            log.info("본문 적재 완료: requestPaperId={}, documentId={}", requestPaperId, documentId);
        }
    }
}
