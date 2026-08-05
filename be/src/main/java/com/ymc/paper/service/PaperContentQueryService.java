package com.ymc.paper.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperContent;
import com.ymc.paper.domain.PaperContentAssetRepository;
import com.ymc.paper.domain.PaperContentBlockRepository;
import com.ymc.paper.domain.PaperContentRepository;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.port.PresignedDownload;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 본문 조회. 호출 1회 = SELECT 4개 고정 — 블록 수와 무관하다.
 */
@Service
@RequiredArgsConstructor
public class PaperContentQueryService {

    private final PaperRepository paperRepository;
    private final PaperContentRepository contentRepository;
    private final PaperContentBlockRepository blockRepository;
    private final PaperContentAssetRepository assetRepository;
    private final AssetUrlCache assetUrlCache;

    /**
     * @throws ApiException PAPER_NOT_FOUND(404) — 논문 없음
     * @throws ApiException FORBIDDEN(403) — 소유자가 아님
     * @throws ApiException PAPER_NOT_READY(409) — COMPLETED가 아니거나 아직 미적재
     */
    @Transactional(readOnly = true)
    public PaperContentView getContent(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다."));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        if (paper.getStatus() != PaperStatus.COMPLETED) {
            throw new ApiException(ErrorCode.PAPER_NOT_READY,
                    "논문이 아직 완료 상태가 아닙니다: " + paper.getStatus());
        }
        PaperContent content = contentRepository.findById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_READY, "본문이 아직 적재되지 않았습니다."));

        List<PaperContentView.Block> blocks = blockRepository
                .findAllByPaperIdOrderByGlobalOrderAsc(paperId).stream()
                .map(b -> new PaperContentView.Block(b.getBlockId(), b.getGlobalOrder(), b.getLabel(),
                        b.getHeadingLevel(), b.getSectionPath(), b.getContent()))
                .toList();

        Map<String, PaperContentView.Asset> assets = new LinkedHashMap<>();
        assetRepository.findAllByPaperId(paperId).forEach(a -> {
            PresignedDownload presigned = assetUrlCache.issue(a.getS3Key());
            assets.put(a.getAssetKey(),
                    new PaperContentView.Asset(presigned.url(), a.getMediaType(), presigned.expiresAt()));
        });

        return new PaperContentView(paperId, content.getTitle(), content.getSchemaVersion(), blocks, assets);
    }
}
