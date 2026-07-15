package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedDownload;

import lombok.RequiredArgsConstructor;

/**
 * 원본 PDF 다운로드용 presigned GET URL 발급 (FT-002 Story 5).
 * 가용 조건은 "S3에 원본 객체가 있음" = status가 UPLOADED 이상이다.
 * 제품 규칙(완료 시에만 노출)은 FE가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class PaperDownloadService {

    private final PaperRepository paperRepository;
    private final FileStorage fileStorage;

    /**
     * @throws ApiException {@code PAPER_NOT_FOUND} — 존재하지 않는 paperId
     * @throws ApiException {@code UPLOAD_NOT_FOUND} — 원본 객체가 아직 없음(UPLOAD_PENDING/EXPIRED)
     */
    @Transactional(readOnly = true)
    public PresignedDownload download(UUID paperId) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId));

        PaperStatus status = paper.getStatus();
        if (status == PaperStatus.UPLOAD_PENDING || status == PaperStatus.EXPIRED) {
            throw new ApiException(
                    ErrorCode.UPLOAD_NOT_FOUND, "업로드된 원본 파일이 없습니다: " + paperId);
        }
        return fileStorage.presignDownload(paper.getFileKey(), paper.getFilename());
    }
}
