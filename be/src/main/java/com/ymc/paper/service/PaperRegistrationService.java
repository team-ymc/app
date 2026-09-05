package com.ymc.paper.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedUpload;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;

import lombok.RequiredArgsConstructor;

/**
 * 논문 등록 — 형식·크기 검증 → {@code UPLOAD_PENDING} 레코드 생성 → presigned PUT URL 발급.
 * 레코드 생성이 업로드보다 먼저다 (ADR-001).
 */
@Service
@RequiredArgsConstructor
public class PaperRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(PaperRegistrationService.class);

    /** MVP는 PDF만 받는다 (계약 `CreatePaperRequest.contentType` enum). */
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    private final PaperRepository paperRepository;
    private final FileStorage fileStorage;
    private final PaperUploadPolicy uploadPolicy;
    private final UsageService usageService;

    /**
     * 레코드를 만들고 업로드 URL을 발급한다.
     *
     * @throws ApiException {@code UNSUPPORTED_FILE_TYPE} — contentType이 PDF가 아님
     * @throws ApiException {@code FILE_TOO_LARGE} — 신고된 크기가 상한을 넘음. 레코드를 만들지 않는다
     */
    @Transactional
    public PaperRegistrationResult register(
            UUID ownerId, String filename, String contentType, long size, String checksumSha256) {

        // 1. PDF 타입 검사
        if (!PDF_CONTENT_TYPE.equals(contentType)) {
            throw new ApiException(
                    ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "지원하지 않는 파일 형식입니다: " + contentType);
        }

        // 2. 크기 상한 — 이 값이 그대로 서명에 들어가므로 여기서 막으면 S3도 초과분을 받지 않는다
        if (uploadPolicy.exceedsLimit(size)) {
            // FE가 먼저 막으므로 여기까지 온 건 FE를 거치지 않은 요청이다
            log.warn("업로드 크기 상한 초과: ownerId={}, size={}, limit={}",
                    ownerId, size, uploadPolicy.maxFileBytes());
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, uploadPolicy.tooLargeMessage());
        }

        Paper paper = Paper.register(ownerId, filename, Instant.now());
        usageService.reserve(ownerId, UsageType.PAPER_REGISTRATION, paper.getId());
        paperRepository.save(paper);

        // S3는 외부 I/O가 발생하지만,
        // presign은 S3 호출이 아니라 로컬 서명 계산이라 트랜잭션 안에서 해도 외부 I/O가 없다.
        PresignedUpload upload = fileStorage.presignUpload(
                paper.getFileKey(), contentType, size, checksumSha256);

        return new PaperRegistrationResult(
                paper.getId(),
                paper.getFileKey(),
                Map.of("Content-Type", contentType, "x-amz-checksum-sha256", checksumSha256),
                upload.url(),
                upload.expiresAt(),
                PaperStatus.UPLOAD_PENDING,
                paper.getCreatedAt());
    }
}
