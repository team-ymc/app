package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;

import lombok.RequiredArgsConstructor;

/**
 * 채팅(FT-007)이 요구하는 논문 접근 검증. chat 컨텍스트는 paper를 ID로만 알므로
 * (be/CLAUDE.md 의존성 규칙) 검증은 paper 컨텍스트가 서비스로 제공한다.
 *
 * <p>학습 페이지 진입은 파싱 완료(COMPLETED) 논문에서만 가능하다 (계약 PaperListItem 주석).
 */
@Service
@RequiredArgsConstructor
public class PaperChatAccessValidator {

    private final PaperRepository paperRepository;
    private final PaperDocumentViews views;

    /**
     * @throws ApiException PAPER_NOT_FOUND(404) — 논문 없음
     * @throws ApiException FORBIDDEN(403) — 소유자가 아님
     * @throws ApiException PAPER_NOT_READY(409) — 파싱 완료 상태가 아님
     */
    @Transactional(readOnly = true)
    public void validateChatReady(UUID paperId, UUID ownerId) {
        Paper paper = getOwned(paperId, ownerId);
        PaperStatus status = PaperDocumentViews.derivedStatus(paper, views.documentOf(paper).orElse(null));
        if (status != PaperStatus.COMPLETED) {
            throw new ApiException(ErrorCode.PAPER_NOT_READY,
                    "논문이 아직 학습 가능한 상태가 아닙니다: " + status);
        }
    }

    /**
     * 채팅 가능 검증 + AI가 패키지를 찾는 식별자 반환. AI 패키지 경로는 파싱 요청의
     * paper_id(= Document.requestPaperId) 기준이라 중복 연결 논문에서는 Paper.id와 다르다.
     *
     * @throws ApiException PAPER_NOT_FOUND(404) / FORBIDDEN(403) / PAPER_NOT_READY(409)
     */
    @Transactional(readOnly = true)
    public UUID requireReadyRequestPaperId(UUID paperId, UUID ownerId) {
        Paper paper = getOwned(paperId, ownerId);
        Document document = views.documentOf(paper).orElse(null);
        PaperStatus status = PaperDocumentViews.derivedStatus(paper, document);
        if (status != PaperStatus.COMPLETED) {
            throw new ApiException(ErrorCode.PAPER_NOT_READY,
                    "논문이 아직 학습 가능한 상태가 아닙니다: " + status);
        }
        return document.getRequestPaperId(); // COMPLETED면 document는 null이 아니다
    }

    /**
     * 소유만 검증한다 — 세션 히스토리 조회·삭제는 논문 파싱 상태와 무관하다 (YMC-260 설계 §3).
     *
     * @throws ApiException PAPER_NOT_FOUND(404) — 논문 없음
     * @throws ApiException FORBIDDEN(403) — 소유자가 아님
     */
    @Transactional(readOnly = true)
    public void validateOwned(UUID paperId, UUID ownerId) {
        getOwned(paperId, ownerId);
    }

    private Paper getOwned(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findActiveById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다."));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        return paper;
    }
}
