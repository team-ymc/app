package com.ymc.paper.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;

import lombok.RequiredArgsConstructor;

/**
 * 컴파일 결과 반영. 병합과 상태 종결을 한 트랜잭션으로 묶는다 — 병합만 커밋되고 상태가 REQUESTED로 남거나,
 * 상태만 COMPLETED가 되고 번역이 없는 상태를 만들지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeCompileResultService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCompileResultService.class);

    private final DocumentRepository documentRepository;
    private final DocumentTransitions transitions;
    private final DocumentTranslationMergeService mergeService;

    @Transactional
    public void apply(UUID requestPaperId, CompileStatus terminal, String errorCode, String manifestKey) {
        Optional<Document> found = documentRepository.findByRequestPaperId(requestPaperId);
        if (found.isEmpty()) {
            log.warn("컴파일 결과 미반영, 대응 document 없음: requestPaperId={}", requestPaperId);
            return;
        }
        Document document = found.get();
        UUID documentId = document.getId();
        if (document.getCompileStatus() == CompileStatus.COMPLETED
                || document.getCompileStatus() == CompileStatus.FAILED) {
            log.info("컴파일 결과 미반영, 이미 종결: requestPaperId={}, documentId={}, compileStatus={}",
                    requestPaperId, documentId, document.getCompileStatus());
            return;
        }
        if (document.getStatus() != DocumentStatus.COMPLETED) {
            log.warn("컴파일 결과 미반영, 파싱이 COMPLETED가 아님: requestPaperId={}, documentId={}, status={}",
                    requestPaperId, documentId, document.getStatus());
            return;
        }

        if (terminal == CompileStatus.COMPLETED) {
            int merged = mergeService.merge(documentId, manifestKey);
            if (merged == 0 && "en".equals(document.getSourceLanguage())) {
                log.warn("영어 문서인데 병합된 번역이 없습니다, READY로 응답되지만 번역 블록 없음: requestPaperId={}, "
                        + "documentId={}, manifestKey={}", requestPaperId, documentId, manifestKey);
            }
            transitions.markCompiled(documentId, CompileStatus.COMPLETED, null, null);
            log.info("컴파일 완료 반영: requestPaperId={}, documentId={}, mergedBlocks={}",
                    requestPaperId, documentId, merged);
            return;
        }
        transitions.markCompiled(documentId, CompileStatus.FAILED, errorCode, null);
        log.error("컴파일 실패 기록: requestPaperId={}, documentId={}, code={}", requestPaperId, documentId, errorCode);
    }
}
