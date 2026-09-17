package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.KnowledgeGraphStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.service.port.PresignedDownload;

import lombok.RequiredArgsConstructor;

/**
 * 지식 그래프 viz.html을 여는 presigned GET URL 발급. 발급 전이 유일한 검증 지점이다 — 발급된 URL은 BE를 거치지 않는다.
 * 만료 창 안에서는 같은 URL을 돌려줘 브라우저 캐시가 산다.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeGraphViewService {

    private final PaperRepository paperRepository;
    private final PaperDocumentViews views;
    private final AssetUrlCache assetUrlCache;

    /**
     * @throws ApiException {@code PAPER_NOT_FOUND} — 존재하지 않는 paperId
     * @throws ApiException {@code FORBIDDEN} — 소유자가 아님
     * @throws ApiException {@code KNOWLEDGE_GRAPH_NOT_READY} — Document 미연결이거나 READY가 아님
     */
    @Transactional(readOnly = true)
    public PresignedDownload view(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findActiveById(paperId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        Document document = views.documentOf(paper).orElse(null);
        if (document == null || document.knowledgeGraphStatus() != KnowledgeGraphStatus.READY) {
            throw new ApiException(ErrorCode.KNOWLEDGE_GRAPH_NOT_READY, "지식 그래프가 아직 준비되지 않았습니다: " + paperId);
        }
        return assetUrlCache.issue(document.getKnowledgeGraphKey());
    }
}
