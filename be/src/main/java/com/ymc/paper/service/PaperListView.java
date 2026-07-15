package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;

/** 목록 응답의 재료. 엔티티를 api로 넘기지 않기 위한 값 (be/CLAUDE.md). */
public record PaperListView(UUID paperId, String filename, PaperStatus status,
                            Instant createdAt, Instant updatedAt) {

    public static PaperListView from(Paper paper) {
        return new PaperListView(paper.getId(), paper.getFilename(), paper.getStatus(),
                paper.getCreatedAt(), paper.getUpdatedAt());
    }
}
