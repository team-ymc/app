package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/** 폴링용 상태 조회 (FT-003 Story 6). FE가 새로고침 없이 상태를 반영하기 위해 주기적으로 호출한다. */
@Service
@RequiredArgsConstructor
public class PaperStatusService {

    private final PaperRepository paperRepository;

    /** @throws ApiException {@code PAPER_NOT_FOUND} — 존재하지 않는 paperId */
    @Transactional(readOnly = true)
    public PaperStatusView getStatus(UUID paperId) {
        return paperRepository.findById(paperId)
                .map(PaperStatusView::from)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId));
    }
}
