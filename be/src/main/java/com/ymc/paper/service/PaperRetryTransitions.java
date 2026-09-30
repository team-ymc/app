package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.RetryStart.Kind;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;

import lombok.RequiredArgsConstructor;

/**
 * 재시도의 DB 변경을 한 트랜잭션으로 끝낸다. Document 행을 잠가 정산·연결·다른 재시도와 직렬화한다.
 * 잠금 순서는 document 다음 usage_bucket이다 — 반대 순서로 잠그는 코드를 만들면 교착이 난다.
 */
@Service
@RequiredArgsConstructor
public class PaperRetryTransitions {

    private final PaperRepository paperRepository;
    private final DocumentRepository documentRepository;
    private final UsageService usageService;

    /**
     * @throws ApiException PAPER_NOT_FOUND(404), FORBIDDEN(403), PAPER_NOT_RETRYABLE(409),
     *         RETRY_LIMIT_EXCEEDED(409), PAPER_USAGE_LIMIT_EXCEEDED(429)
     */
    @Transactional
    public RetryStart begin(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findActiveById(paperId).orElseThrow(() ->
                new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        if (paper.getDocumentId() == null) {
            throw notRetryable();
        }
        // Document는 잠금 조회로 처음 읽는다 — 먼저 읽으면 잠금 대기 중 바뀐 상태를 놓친다
        Document document = documentRepository.findWithLockById(paper.getDocumentId())
                .orElseThrow(() -> new IllegalStateException(
                        "연결된 document가 없습니다: paperId=" + paperId));

        PaperStatus status = PaperDocumentViews.derivedStatus(paper, document);
        return switch (status) {
            case FAILED -> beginParsing(paper, document);
            case UPLOADED, PROCESSING -> RetryStart.of(Kind.WAITING, document);
            case COMPLETED -> beginCompile(document);
            default -> throw notRetryable();
        };
    }

    private RetryStart beginParsing(Paper paper, Document document) {
        Instant now = Instant.now();
        UUID paperId = paper.getId();
        return switch (document.getStatus()) {
            case COMPLETED -> {
                reclaim(paper, now);
                usageService.confirm(UsageType.PAPER_REGISTRATION, paperId, null);
                yield RetryStart.of(Kind.SETTLED, document);
            }
            case UPLOADED, PROCESSING -> {
                reclaim(paper, now);
                yield RetryStart.of(Kind.WAITING, document);
            }
            case FAILED -> {
                if (!document.parseAttemptsLeft()) {
                    throw new ApiException(
                            ErrorCode.RETRY_LIMIT_EXCEEDED, "처리할 수 없는 파일입니다.");
                }
                reclaim(paper, now);
                paperRepository.markFailedOthers(
                        document.getId(), paperId, document.getErrorCode(), now);
                if (documentRepository.markRetrying(
                        document.getId(), Document.MAX_ATTEMPTS, now) != 1) {
                    throw new IllegalStateException(
                            "잠금 안에서 재시도 전이가 실패했습니다: documentId=" + document.getId());
                }
                yield RetryStart.of(Kind.PUBLISH_PARSE, document);
            }
        };
    }

    /** 파싱은 이미 확정됐다 — 사용량과 환불 표시는 건드리지 않는다. */
    private RetryStart beginCompile(Document document) {
        CompileStatus compile = document.getCompileStatus();
        if (compile == CompileStatus.REQUESTED) {
            return RetryStart.of(Kind.WAITING, document);
        }
        if (compile != CompileStatus.FAILED) {
            throw notRetryable();
        }
        if (!document.compileAttemptsLeft()) {
            throw new ApiException(ErrorCode.RETRY_LIMIT_EXCEEDED,
                    "번역과 지식 그래프를 더 이상 다시 만들 수 없습니다.");
        }
        if (documentRepository.markCompileRetrying(
                document.getId(), Document.MAX_ATTEMPTS) != 1) {
            throw new IllegalStateException(
                    "잠금 안에서 컴파일 재요청 전이가 실패했습니다: documentId=" + document.getId());
        }
        return RetryStart.of(Kind.PUBLISH_COMPILE, document);
    }

    /** 다시 예약하고 환불 표시를 지운다. 한도가 찼으면 예외로 끝나 아무것도 바뀌지 않는다. */
    private void reclaim(Paper paper, Instant now) {
        usageService.reReserve(paper.getOwnerId(), UsageType.PAPER_REGISTRATION, paper.getId());
        paperRepository.clearFailed(paper.getId(), now);
    }

    private static ApiException notRetryable() {
        return new ApiException(ErrorCode.PAPER_NOT_RETRYABLE, "다시 시도할 수 있는 상태가 아닙니다.");
    }
}
