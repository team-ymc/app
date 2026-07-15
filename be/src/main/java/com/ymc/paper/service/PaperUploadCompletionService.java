package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.ParseRequestPublisher;

import lombok.RequiredArgsConstructor;

/**
 * 업로드 완료 통보 → 파싱 요청 발행 (design D6).
 *
 * <pre>
 * paper 조회 → 이미 전이됨이면 HEAD·재발행 없이 현재 상태 200 (멱등)
 *              └ UPLOAD_PENDING이면 S3 HEAD → CAS(UPLOAD_PENDING→UPLOADED) 커밋
 *                                             → 큐 발행
 *                                             → UPLOADED→PROCESSING 커밋 → 200
 * </pre>
 *
 * <p><b>이 클래스에 {@code @Transactional}을 걸지 말 것.</b> 세 단계는 서로 다른 트랜잭션이어야 한다 —
 * 경계는 {@link PaperTransitions}가 갖는다 (tasks 4.3).
 */
@Service
@RequiredArgsConstructor
public class PaperUploadCompletionService {

    private static final Logger log = LoggerFactory.getLogger(PaperUploadCompletionService.class);

    private final PaperRepository paperRepository;
    private final PaperTransitions transitions;
    private final FileStorage fileStorage;
    private final ParseRequestPublisher parseRequestPublisher;

    /**
     * @throws ApiException {@code PAPER_NOT_FOUND} — 존재하지 않는 paperId (S3는 조회하지 않는다)
     * @throws ApiException {@code UPLOAD_NOT_FOUND} — S3에 객체가 없음. 상태는 UPLOAD_PENDING 유지
     */
    public PaperStatusView complete(UUID paperId) {
        Paper paper = find(paperId);

        // 이미 전이된 레코드는 HEAD도 재발행도 하지 않고 현재 상태를 돌려준다. 객체의 사후 삭제나
        // S3 일시 장애가 이미 끝난 complete의 결과를 바꾸지 않게 한다 (계약의 멱등 규칙).
        if (paper.getStatus() != PaperStatus.UPLOAD_PENDING) {
            return PaperStatusView.from(paper);
        }

        if (!fileStorage.exists(paper.getFileKey())) {
            throw new ApiException(
                    ErrorCode.UPLOAD_NOT_FOUND,
                    "업로드된 파일을 찾을 수 없습니다. 업로드 후 다시 시도해 주세요.");
        }

        // (1) CAS 커밋 — 동시 complete 중 한 건만 주인이 된다
        if (!transitions.markUploaded(paperId)) {
            // 동시 요청이 먼저 전이했다. 재발행하지 않고 최신 상태를 다시 읽어 돌려준다.
            return PaperStatusView.from(find(paperId));
        }

        // (2) 큐 발행 — 트랜잭션 밖. 실패하면 예외가 올라가 5xx가 되고 레코드는 UPLOADED에 남는다.
        //     순서 주의: 반드시 UPLOADED 커밋(1) 후 발행
        //     뒤집으면(발행 먼저) 커밋 실패 시 재시도 → 중복 파싱(고 비용).
        //     - "중복(비용)보다 누락(정체)이 낫다". (로그로 남음)
        //     ADR-001 §5가 문서화한 MVP 갭이라 복구 장치를 두지 않는다 (post-MVP reconciliation batch).
        try {
            parseRequestPublisher.publish(paperId, paper.getFileKey());
        } catch (RuntimeException e) {
            // 복구하지 않기로 한 갭이라 WARN이 UPLOADED 정체를 알아챌 유일한 수단
            log.warn("파싱 요청 발행 실패, UPLOADED 정체: paperId={}, fileKey={}",
                    paperId, paper.getFileKey(), e);
            throw e;
        }
        log.info("파싱 요청 발행: paperId={}, fileKey={}", paperId, paper.getFileKey());

        // (3) PROCESSING 커밋 — 실패해도 파싱은 이미 진행 중이다. 마찬가지로 방치한다.
        try {
            return transitions.markProcessing(paperId);
        } catch (RuntimeException e) {
            // 발행은 이미 나갔다. 결과가 와도 PROCESSING CAS가 0 row라 반영되지 않는다.
            log.warn("PROCESSING 전이 실패, 발행 후 UPLOADED 정체: paperId={}", paperId, e);
            throw e;
        }
    }

    private Paper find(UUID paperId) {
        return paperRepository.findById(paperId).orElseThrow(() -> {
            log.debug("존재하지 않는 paperId로 complete 호출: {}", paperId);
            return new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId);
        });
    }
}
