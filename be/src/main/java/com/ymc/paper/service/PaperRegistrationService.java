package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedUpload;

import lombok.RequiredArgsConstructor;

/**
 * 논문 등록 — 중복 판정 → {@code UPLOAD_PENDING} 레코드 생성 → presigned PUT URL 발급.
 * 레코드 생성이 업로드보다 먼저다 (ADR-001).
 */
@Service
@RequiredArgsConstructor
public class PaperRegistrationService {

    /** MVP는 PDF만 받는다 (계약 `CreatePaperRequest.contentType` enum). */
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    private final PaperRepository paperRepository;
    private final FileStorage fileStorage;

    /**
     * 파일명이 중복이 아니면 레코드를 만들고 업로드 URL을 발급한다.
     *
     * <p>중복 판정은 사전 조회 + DB 유니크 제약 이중 방어다 (design D4). 사전 조회를 나란히 통과한
     * 동시 요청은 제약 위반으로 잡아 같은 409로 변환한다.
     *
     * @throws ApiException {@code UNSUPPORTED_FILE_TYPE} — contentType이 PDF가 아님
     * @throws ApiException {@code DUPLICATE_FILENAME} — 같은 소유자에게 같은 파일명이 이미 있음
     */
    @Transactional
    public PaperRegistrationResult register(UUID ownerId, String filename, String contentType) {

        // 1. PDF 타입 검사
        if (!PDF_CONTENT_TYPE.equals(contentType)) {
            throw new ApiException(
                    ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "지원하지 않는 파일 형식입니다: " + contentType);
        }

        // 2. 사전 조회
        if (paperRepository.existsByOwnerIdAndFilename(ownerId, filename)) {
            throw duplicateFilename(filename);
        }

        Paper paper = Paper.register(ownerId, filename, Instant.now());

        // flush를 앞당겨 유니크 제약 위반 방지하여 적절한 에러코드 반환.
        try {
            paperRepository.saveAndFlush(paper);
        } catch (DataIntegrityViolationException e) {
            throw duplicateFilename(filename);
        }

        // S3는 외부 I/O가 발생하지만,
        // presign은 S3 호출이 아니라 로컬 서명 계산이라 트랜잭션 안에서 해도 외부 I/O가 없다.
        PresignedUpload upload = fileStorage.presignUpload(paper.getFileKey(), contentType);

        return new PaperRegistrationResult(
                paper.getId(),
                paper.getFileKey(),
                upload.url(),
                upload.expiresAt(),
                paper.getStatus(),
                paper.getCreatedAt());
    }

    private static ApiException duplicateFilename(String filename) {
        return new ApiException(
                ErrorCode.DUPLICATE_FILENAME, "같은 파일명의 논문이 이미 있습니다: " + filename);
    }
}
