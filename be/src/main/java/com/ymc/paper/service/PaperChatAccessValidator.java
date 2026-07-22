package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
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

    /**
     * @throws ApiException PAPER_NOT_FOUND(404) — 논문 없음
     * @throws ApiException FORBIDDEN(403) — 소유자가 아님
     * @throws ApiException PAPER_NOT_READY(409) — 파싱 완료 상태가 아님
     */
    @Transactional(readOnly = true)
    public void validateChatReady(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다."));

        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        if (paper.getStatus() != PaperStatus.COMPLETED) {
            throw new ApiException(ErrorCode.PAPER_NOT_READY,
                    "논문이 아직 학습 가능한 상태가 아닙니다: " + paper.getStatus());
        }
    }
}
