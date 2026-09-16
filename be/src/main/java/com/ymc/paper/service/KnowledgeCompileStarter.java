package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.service.port.KnowledgeCompileRequestPublisher;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 완료·적재 뒤 지식 컴파일 요청 발행. 선점 CAS 승자만 발행하고, 발행은 트랜잭션 밖이다.
 * 선점 커밋 뒤 발행 전에 프로세스가 죽으면 REQUESTED에 머문다 — 파싱 발행의 PROCESSING 정체와 같은 창이며
 * 정체 정리 스윕의 후속 범위다.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeCompileStarter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCompileStarter.class);

    private final DocumentRepository documentRepository;
    private final DocumentTransitions transitions;
    private final KnowledgeCompileRequestPublisher publisher;

    public void startIfCompleted(UUID documentId, String manifestKey) {
        Document document = documentRepository.findById(documentId).orElseThrow(
                () -> new IllegalStateException("존재하지 않는 document: " + documentId));
        if (document.getStatus() != DocumentStatus.COMPLETED || document.getCompileStatus() != null) {
            return;
        }
        if (!transitions.markCompileRequested(documentId)) {
            return;   // 다른 수신이 선점
        }
        try {
            publisher.publish(document.getRequestPaperId(), manifestKey);
        } catch (RuntimeException e) {
            revertBestEffort(documentId);
            log.warn("컴파일 요청 발행 실패, 선점 반납: documentId={}, requestPaperId={}",
                    documentId, document.getRequestPaperId(), e);
            throw e;
        }
        log.info("컴파일 요청 발행: documentId={}, requestPaperId={}, manifestKey={}",
                documentId, document.getRequestPaperId(), manifestKey);
    }

    private void revertBestEffort(UUID documentId) {
        try {
            transitions.revertCompileRequested(documentId);
        } catch (RuntimeException revertFailure) {
            log.warn("REQUESTED 반납 실패, 정체 가능: documentId={}", documentId, revertFailure);
        }
    }
}
