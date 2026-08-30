package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.service.PaperDocumentLinkService.LinkOutcome;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.UploadedObjectMetadata;

import lombok.RequiredArgsConstructor;

/**
 * 업로드 완료 통보 — S3 검증 checksum으로 Document를 결정하고 발행 규칙을 실행한다.
 *
 * <pre>
 * paper 조회·소유 확인
 * → 이미 연결됨: 발행 규칙(정체 구제 창구) 후 파생 상태 반환 (HEAD 없음)
 * → 미연결: HEAD(checksum 포함) 검증 → [Tx] Document 생성/연결 → 중복 객체 삭제(best-effort)
 *          → 발행 규칙 → 파생 상태 반환
 * </pre>
 *
 * <p><b>이 클래스에 {@code @Transactional}을 걸지 말 것.</b> 연결 커밋·선점 커밋·큐 발행은
 * 서로 다른 경계여야 한다 — 경계는 {@link PaperDocumentLinkService}·{@link DocumentTransitions}가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class PaperUploadCompletionService {

    private static final Logger log = LoggerFactory.getLogger(PaperUploadCompletionService.class);

    private final PaperRepository paperRepository;
    private final PaperDocumentLinkService linkService;
    private final DocumentParsingStarter parsingStarter;
    private final PaperDocumentViews views;
    private final FileStorage fileStorage;
    private final PaperUploadPolicy uploadPolicy;

    public PaperStatusView complete(UUID paperId, UUID ownerId) {
        Paper paper = find(paperId);
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }

        if (paper.getExpiredAt() != null) {
            // 만료가 먼저 커밋됐으면 예약은 이미 반환됐다 — 업로드를 이어가지 않는다
            throw new ApiException(ErrorCode.UPLOAD_EXPIRED, "업로드가 만료된 논문입니다. 다시 등록해 주세요.");
        }

        // 멱등 재호출. 발행 규칙을 먼저 거치는 이유: 발행 실패로 UPLOADED에 정체된 Document를
        // 재호출이 구제할 수 있는 유일한 창구이기 때문이다.
        if (paper.getDocumentId() != null) {
            parsingStarter.startIfUploaded(paper.getDocumentId());
            return views.statusView(find(paperId));
        }

        UploadedObjectMetadata uploadedObject = fileStorage.head(paper.getFileKey()).orElseThrow(() ->
                new ApiException(ErrorCode.UPLOAD_NOT_FOUND,
                        "업로드된 파일을 찾을 수 없습니다. 업로드 후 다시 시도해 주세요."));

        if (uploadPolicy.exceedsLimit(uploadedObject.contentLength())) {
            log.warn("상한 초과 객체 삭제: paperId={}, contentLength={}, limit={}",
                    paperId, uploadedObject.contentLength(), uploadPolicy.maxFileBytes());
            fileStorage.delete(paper.getFileKey());
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, uploadPolicy.tooLargeMessage());
        }

        if (uploadedObject.checksumSha256() == null || uploadedObject.checksumSha256().isBlank()) {
            throw new ApiException(ErrorCode.UPLOAD_CHECKSUM_MISSING,
                    "업로드 객체에 검증된 checksum이 없습니다. 같은 파일을 다시 업로드한 뒤 재시도해 주세요.");
        }

        LinkOutcome outcome = linkService.linkOrCreate(
                paperId, paper.getFileKey(), uploadedObject.checksumSha256());
        log.info("document {}: paperId={}, documentId={}",
                outcome.linkedToExisting() ? "연결" : "생성", paperId, outcome.document().getId());

        // 연결 커밋 후에만 삭제. 대표 원본과 같은 key면 절대 지우지 않는다 —
        // 같은 Paper의 동시 complete에서 패자가 대표 원본을 지우는 사고 방지.
        if (outcome.linkedToExisting()
                && !paper.getFileKey().equals(outcome.document().getFileKey())) {
            deleteBestEffort(paperId, outcome.document().getId(), paper.getFileKey());
        }

        parsingStarter.startIfUploaded(outcome.document().getId());
        return views.statusView(find(paperId));
    }

    private void deleteBestEffort(UUID paperId, UUID documentId, String fileKey) {
        try {
            fileStorage.delete(fileKey);
        } catch (RuntimeException e) {
            // 잔여 객체는 후속 정리 작업이 재삭제한다 — complete를 실패로 되돌리지 않는다
            log.warn("중복 업로드 객체 삭제 실패: paperId={}, documentId={}, fileKey={}",
                    paperId, documentId, fileKey, e);
        }
    }

    private Paper find(UUID paperId) {
        return paperRepository.findById(paperId).orElseThrow(() -> {
            log.debug("존재하지 않는 paperId로 complete 호출: {}", paperId);
            return new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId);
        });
    }
}
