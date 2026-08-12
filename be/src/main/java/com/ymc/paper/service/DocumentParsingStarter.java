package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.service.port.ParseRequestPublisher;

import lombok.RequiredArgsConstructor;

/**
 * 발행 규칙 — 생성·연결·멱등 재호출 모든 경로가 이 메서드 하나를 거친다.
 * UPLOADED면 선점(CAS) 승자만 발행하고, 실패하면 반납해 다음 complete가 구제할 수 있게 한다.
 * 메시지 식별자는 언제나 Document에 보존된 최초 paperId다.
 */
@Service
@RequiredArgsConstructor
public class DocumentParsingStarter {

    private static final Logger log = LoggerFactory.getLogger(DocumentParsingStarter.class);

    private final DocumentRepository documentRepository;
    private final DocumentTransitions transitions;
    private final ParseRequestPublisher parseRequestPublisher;

    public void startIfUploaded(UUID documentId) {
        Document document = documentRepository.findById(documentId).orElseThrow(
                () -> new IllegalStateException("존재하지 않는 document: " + documentId));
        if (document.getStatus() != DocumentStatus.UPLOADED) {
            return;
        }
        if (!transitions.markProcessing(documentId)) {
            return;   // 다른 요청이 선점
        }
        try {
            parseRequestPublisher.publish(document.getRequestPaperId(), document.getFileKey());
        } catch (RuntimeException e) {
            revertBestEffort(documentId);
            log.warn("파싱 요청 발행 실패, UPLOADED 반납: documentId={}, requestPaperId={}",
                    documentId, document.getRequestPaperId(), e);
            throw e;
        }
        log.info("파싱 요청 발행: documentId={}, requestPaperId={}",
                documentId, document.getRequestPaperId());
    }

    private void revertBestEffort(UUID documentId) {
        try {
            transitions.revertToUploaded(documentId);
        } catch (RuntimeException revertFailure) {
            // 반납까지 실패하면 PROCESSING 정체 — 후속 정리 스윕(별도 티켓)의 대상
            log.warn("PROCESSING 반납 실패, 정체 가능: documentId={}", documentId, revertFailure);
        }
    }
}
