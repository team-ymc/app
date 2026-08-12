package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/**
 * complete의 "checksum → Document 결정 + Paper 연결"을 한 트랜잭션으로 처리한다.
 * 생성 경쟁의 패자는 ON CONFLICT 0 row로 감지해 기존 Document 연결로 전환한다.
 */
@Service
@RequiredArgsConstructor
public class PaperDocumentLinkService {

    private final DocumentRepository documentRepository;
    private final PaperRepository paperRepository;

    public record LinkOutcome(Document document, boolean linkedToExisting) {
    }

    @Transactional
    public LinkOutcome linkOrCreate(UUID paperId, String fileKey, String checksumSha256) {
        Instant now = Instant.now();
        boolean created = documentRepository.insertIfAbsent(
                UUID.randomUUID(), checksumSha256, fileKey, paperId, now) == 1;
        Document document = documentRepository.findByChecksumSha256(checksumSha256)
                .orElseThrow(() -> new IllegalStateException(
                        "생성 직후 조회에 실패한 document: paperId=" + paperId));
        // 0 row = 같은 Paper의 동시 complete가 먼저 연결 — 결과가 같으므로 그대로 진행
        paperRepository.linkDocument(paperId, document.getId(), now);
        return new LinkOutcome(document, !created);
    }
}
