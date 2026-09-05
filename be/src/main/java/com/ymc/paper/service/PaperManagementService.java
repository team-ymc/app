package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.ChatSessionRepository;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/** 서재의 논문 정리 — 논리 삭제와 이름 변경. */
@Service
@RequiredArgsConstructor
public class PaperManagementService {

    private static final int FILENAME_MAX_LENGTH = 255;

    private final PaperRepository paperRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final PaperDocumentViews views;

    /**
     * 논리 삭제. 소속 채팅 세션도 함께 논리 삭제한다. 원본·Document는 공유 자원이라 건드리지 않는다.
     *
     * @throws ApiException {@code PAPER_NOT_FOUND} — 없거나 이미 삭제됨
     * @throws ApiException {@code FORBIDDEN} — 소유자가 아님
     */
    @Transactional
    public void delete(UUID paperId, UUID ownerId) {
        Paper paper = findOwned(paperId, ownerId);
        Instant now = Instant.now();
        if (paperRepository.markDeleted(paper.getId(), now) == 0) {
            throw notFound(paperId);
        }
        chatSessionRepository.markDeletedByPaperId(paper.getId(), now);
    }

    /**
     * 파일명 변경. trim 후 1~255자만 받는다. 상태 변경이 아니므로 updatedAt은 건드리지 않는다.
     *
     * @throws ApiException {@code VALIDATION_ERROR} — 비었거나 255자 초과
     * @throws ApiException {@code PAPER_NOT_FOUND} / {@code FORBIDDEN}
     */
    @Transactional
    public PaperListView rename(UUID paperId, UUID ownerId, String filename) {
        String normalized = filename == null ? "" : filename.strip();
        if (normalized.isEmpty() || normalized.length() > FILENAME_MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "파일명은 1~255자여야 합니다.");
        }
        Paper paper = findOwned(paperId, ownerId);
        paper.rename(normalized);
        return views.listView(paper);
    }

    private Paper findOwned(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findActiveById(paperId).orElseThrow(() -> notFound(paperId));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        return paper;
    }

    private static ApiException notFound(UUID paperId) {
        return new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId);
    }
}
