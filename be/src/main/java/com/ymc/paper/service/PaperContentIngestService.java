package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.PaperContent;
import com.ymc.paper.domain.PaperContentAsset;
import com.ymc.paper.domain.PaperContentAssetRepository;
import com.ymc.paper.domain.PaperContentBlock;
import com.ymc.paper.domain.PaperContentBlockRepository;
import com.ymc.paper.domain.PaperContentRepository;
import com.ymc.paper.service.port.PaperPackageReader;
import com.ymc.paper.service.port.ParsedPaperPackage;

import lombok.RequiredArgsConstructor;

/**
 * 파서 패키지 → DB 적재. 리스너와 분리된 서비스라 SQS 외 경로
 * (로컬 검증 스크립트·추후 어드민)에서도 호출 가능하다.
 *
 * <p>멱등: 삭제 후 삽입. 삭제는 bulk JPQL이라 호출 즉시 실행되고, 전체가 한 트랜잭션이라
 * 실패 시 부분 적재가 남지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PaperContentIngestService {

    private final PaperPackageReader packageReader;
    private final PaperContentRepository contentRepository;
    private final PaperContentBlockRepository blockRepository;
    private final PaperContentAssetRepository assetRepository;

    @Transactional
    public void ingest(UUID paperId, String manifestKey) {
        ParsedPaperPackage pkg = packageReader.read(manifestKey);

        blockRepository.deleteByPaperId(paperId);
        assetRepository.deleteByPaperId(paperId);
        contentRepository.deleteByPaperId(paperId);

        contentRepository.save(PaperContent.of(paperId, pkg.title(), pkg.schemaVersion(), Instant.now()));
        blockRepository.saveAll(pkg.blocks().stream()
                .map(b -> PaperContentBlock.of(paperId, b.blockId(), b.globalOrder(), b.label(),
                        b.headingLevel(), b.sectionPath(), b.content()))
                .toList());
        assetRepository.saveAll(pkg.assets().stream()
                .map(a -> PaperContentAsset.of(paperId, a.assetKey(), a.s3Key(), a.mediaType()))
                .toList());
    }

    @Transactional(readOnly = true)
    public boolean isIngested(UUID paperId) {
        return contentRepository.existsById(paperId);
    }
}
