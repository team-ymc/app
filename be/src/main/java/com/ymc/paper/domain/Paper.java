package com.ymc.paper.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * 등록·파싱 대상 논문. JPA 엔티티 = 도메인 (ADR-0001 규칙 3).
 *
 * <p>id는 DB가 아니라 BE가 insert 전에 만든다 — fileKey가 id를 포함해야 하고(design D5),
 * presigned URL도 insert 전에 발급 대상 key를 알아야 하기 때문이다.
 *
 * <p>상태는 연결된 {@link Document}가 소유하고 조회 시 파생한다 ({@link
 * com.ymc.paper.service.PaperDocumentViews}) — 엔티티에는 생성 불변식만 남는다.
 */
@Getter
@Entity
@Table(name = "paper")
public class Paper {

    /**
     * 원본 PDF의 S3 key 형식 — uploads/{paperId}/original.pdf.
     * 형식은 계약이 아니라 BE 저장소 내부 구현이다 — AI는 메시지의 fileKey를 그대로 GetObject에
     * 쓴다 (contracts/backend-ai/messaging.yml `ParseRequest.fileKey`). 구형 키 row는 저장된 값을
     * 그대로 발행하므로 마이그레이션하지 않는다 (spec §1 역할 구분).
     */
    private static final String FILE_KEY_FORMAT = "uploads/%s/original.pdf";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 소유자. 인증 주체(JWT subject)가 들어간다 (YMC-215). */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /** 원본 파일명. 서재 목록의 제목이자 다운로드 저장 파일명. 소유자별 중복을 허용한다. */
    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "file_key", nullable = false, updatable = false)
    private String fileKey;

    /** 연결된 공유 Document. 업로드 검증 전에는 null이며 연결은 linkDocument CAS로만 한다. */
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 상태가 마지막으로 바뀐 시각. 행이 바뀐 시각이 아니다 (계약 `PaperStatusResponse.updatedAt`). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 소유자가 마지막으로 접근한 시각(학습 페이지 진입·채팅 발송). 접근 이력이 없으면 null. */
    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    /** 정리 스케줄러가 채우는 만료 시각. 정상 경로에서는 항상 null이다. */
    @Column(name = "expired_at")
    private Instant expiredAt;

    /** 논리 삭제 시각. 값이 있으면 사용자 경로에서 보이지 않는다. 내부 정산·정리 경로는 그대로 본다. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Paper() {
        // JPA
    }

    private Paper(UUID id, UUID ownerId, String filename, Instant now) {
        this.id = id;
        this.ownerId = ownerId;
        this.filename = filename;
        this.fileKey = FILE_KEY_FORMAT.formatted(id);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 업로드 대기 상태의 새 논문. id·fileKey를 함께 만들어 둘의 대응을 엔티티가 보장한다.
     */
    public static Paper register(UUID ownerId, String filename, Instant now) {
        // 기본값 설정
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(now, "now");

        if (filename.isBlank()) {
            throw new IllegalArgumentException("filename은 비어 있을 수 없습니다.");
        }
        return new Paper(UUID.randomUUID(), ownerId, filename, now);
    }

    public void markAccessed(Instant now) {
        this.lastAccessedAt = Objects.requireNonNull(now, "now");
    }

    public void rename(String filename) {
        Objects.requireNonNull(filename, "filename");
        if (filename.isBlank()) {
            throw new IllegalArgumentException("filename은 비어 있을 수 없습니다.");
        }
        this.filename = filename;
    }

    public void markDeleted(Instant now) {
        this.deletedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
