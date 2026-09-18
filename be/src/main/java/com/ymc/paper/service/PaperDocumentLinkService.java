package com.ymc.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;

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
    private final UsageService usageService;

    /** duplicateOfPaperId가 있으면 연결하지 않고 이 Paper를 제거한 것이다. */
    public record LinkOutcome(Document document, boolean linkedToExisting, UUID duplicateOfPaperId) {
    }

    @Transactional
    public LinkOutcome linkOrCreate(UUID paperId, UUID ownerId, String fileKey, String checksumSha256) {
        Instant now = Instant.now();
        boolean created = documentRepository.insertIfAbsent(
                UUID.randomUUID(), checksumSha256, fileKey, paperId, now) == 1;
        // 잠금 조회 — 같은 Paper의 requestPaperId 경쟁을 먼저 흡수하고, 다른 Paper가 만든
        // 같은 checksum Document로 폴백한다. 종결 전이와도 같은 행에서 직렬화된다.
        Document document = documentRepository.findWithLockByRequestPaperId(paperId)
                .or(() -> documentRepository.findWithLockByChecksumSha256(checksumSha256))
                .orElseThrow(() -> new IllegalStateException(
                        "insert 충돌 후 조회에 실패한 document: paperId=" + paperId));

        // Document 행 잠금 안이라 같은 소유자의 동시 등록도 하나만 통과한다
        List<UUID> sameOwnerPaperIds =
                paperRepository.findSameOwnerActiveIds(ownerId, document.getId(), paperId);
        if (!sameOwnerPaperIds.isEmpty()) {
            paperRepository.markDeleted(paperId, now);
            usageService.release(UsageType.PAPER_REGISTRATION, paperId);
            return new LinkOutcome(document, true, sameOwnerPaperIds.get(0));
        }
        int linked = paperRepository.linkDocument(paperId, document.getId(), now);
        if (linked == 0) {
            verifyIdempotentLinkOrThrowExpired(paperId, document.getId());
        }
        if (document.getStatus() == DocumentStatus.COMPLETED) {
            usageService.confirm(UsageType.PAPER_REGISTRATION, paperId, null);
        } else if (document.getStatus() == DocumentStatus.FAILED) {
            usageService.release(UsageType.PAPER_REGISTRATION, paperId);
        }
        return new LinkOutcome(document, !created, null);
    }

    /** 연결 CAS 0행의 원인을 구분한다 — 동일 Document면 멱등, 만료·대체됐으면 complete를 중단한다. */
    private void verifyIdempotentLinkOrThrowExpired(UUID paperId, UUID documentId) {
        Paper current = paperRepository.findById(paperId).orElse(null);
        if (current == null || current.getExpiredAt() != null) {
            throw new ApiException(
                    ErrorCode.UPLOAD_EXPIRED, "업로드가 만료된 논문입니다. 다시 등록해 주세요.");
        }
        if (!documentId.equals(current.getDocumentId())) {
            throw new IllegalStateException(
                    "Paper 연결 CAS가 실패했지만 동일 Document 연결도 만료도 아닙니다: paperId=" + paperId);
        }
    }
}
