package com.ymc.paper.service;

import java.time.Duration;
import java.time.Instant;
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
    private final KnowledgeCompileStarter compileStarter;
    private final ParseMetrics metrics;

    public void apply(UUID requestPaperId, DocumentStatus terminal, String errorCode,
            String manifestKey) {
        Optional<Document> found = documentRepository.findByRequestPaperId(requestPaperId);
        if (found.isEmpty()) {
            log.warn("파싱 결과 미반영, 대응 document 없음: requestPaperId={}", requestPaperId);
            return;
        }
        Document document = found.get();
        UUID documentId = document.getId();
        // PROCESSING 전이 시각이 곧 요청 발행 시각. 선도착(UPLOADED)이나 중복 결과는 lead time을 재지 않는다
        Instant requestedAt = document.getStatus() == DocumentStatus.PROCESSING ? document.getUpdatedAt() : null;

        boolean transitioned = transitions.markParsedAndSettle(documentId, terminal, errorCode);
        if (transitioned) {
            log.info("파싱 결과 반영: requestPaperId={}, documentId={}, status={}",
                    requestPaperId, documentId, terminal);
            if (requestedAt != null) {
                metrics.leadTime(terminal, Duration.between(requestedAt, Instant.now()));
            }
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
        if (!completed) {
            return;
        }
        if (!ingestService.isIngested(documentId)) {
            ingestService.ingest(documentId, manifestKey);
            log.info("본문 적재 완료: requestPaperId={}, documentId={}", requestPaperId, documentId);
        }
        // 본문·채팅은 적재 직후 열리고, 번역은 컴파일 결과를 받은 뒤 붙는다. 발행 실패는 예외로 올려 재전달을 받는다.
        compileStarter.startIfCompleted(documentId, manifestKey);
    }
}
