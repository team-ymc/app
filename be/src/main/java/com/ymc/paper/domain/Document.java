package com.ymc.paper.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 같은 파일 바이트(SHA-256)의 공유 단위 — 대표 원본·파싱 상태·산출물을 소유한다.
 * 여러 Paper가 참조하며 상태 전이는 전부 {@link DocumentRepository}의 CAS로 한다.
 */
@Getter
@Entity
@Table(
        name = "document",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_checksum", columnNames = "checksum_sha256"),
        indexes = @Index(
                name = "ux_document_request_paper", columnList = "request_paper_id", unique = true))
public class Document {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** S3가 실제 바이트와 대조 검증한 표준 Base64 SHA-256. 동일 파일 판정의 유일한 기준. */
    @Column(name = "checksum_sha256", nullable = false, updatable = false, length = 44)
    private String checksumSha256;

    /** 대표 원본 key. 최초 업로드 객체를 복제 없이 그대로 쓴다. */
    @Column(name = "file_key", nullable = false, updatable = false)
    private String fileKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DocumentStatus status;

    /** 파싱 실패 코드 원문. 내부 기록용 — API 응답에는 나가지 않는다. */
    @Column(name = "error_code")
    private String errorCode;

    /**
     * AI 작업 상관키 = 이 Document를 만든 최초 paperId. FK가 아니다 — 대표 Paper가 삭제돼도
     * 발행·결과 역조회는 이 값만 본다.
     */
    @Column(name = "request_paper_id", nullable = false, updatable = false)
    private UUID requestPaperId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 상태가 마지막으로 바뀐 시각. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
        // JPA
    }

    private Document(UUID id, String checksumSha256, String fileKey, UUID requestPaperId, Instant now) {
        this.id = id;
        this.checksumSha256 = checksumSha256;
        this.fileKey = fileKey;
        this.status = DocumentStatus.UPLOADED;
        this.requestPaperId = requestPaperId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Document create(
            UUID id, String checksumSha256, String fileKey, UUID requestPaperId, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(checksumSha256, "checksumSha256");
        Objects.requireNonNull(fileKey, "fileKey");
        Objects.requireNonNull(requestPaperId, "requestPaperId");
        Objects.requireNonNull(now, "now");
        return new Document(id, checksumSha256, fileKey, requestPaperId, now);
    }
}
