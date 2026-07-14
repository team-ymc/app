package com.ymc.paper.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 등록·파싱 대상 논문. JPA 엔티티 = 도메인 (ADR-0001 규칙 3).
 *
 * <p>id는 DB가 아니라 BE가 insert 전에 만든다 — fileKey가 id를 포함해야 하고(design D5),
 * presigned URL도 insert 전에 발급 대상 key를 알아야 하기 때문이다.
 *
 * <p>{@code UPLOAD_PENDING → UPLOADED}와 {@code PROCESSING → terminal}은 여기 메서드가 아니라
 * {@link PaperRepository}의 조건부 UPDATE로 한다 — 동시 요청·중복 수신이 실재하는 전이라
 * load-modify-save로는 lost update를 막을 수 없다 (design D2). 경쟁이 없는 전이만 여기 둔다.
 */
@Getter
@Entity
@Table(
        name = "paper",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_paper_owner_filename",
                columnNames = {"owner_id", "filename"}))
public class Paper {

    /** 계약(openapi.yaml `PaperCreated.fileKey`)의 형식. */
    private static final String FILE_KEY_FORMAT = "papers/%s/original.pdf";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 소유자. 인증 도입(FT-001) 전까지 설정된 고정값이 들어간다 (design D3). */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /** 원본 파일명. 소유자별 중복 판정의 기준이자 서재 목록의 제목 (FT-003 Story 2). */
    @Column(name = "filename", nullable = false, updatable = false)
    private String filename;

    @Column(name = "file_key", nullable = false, updatable = false)
    private String fileKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaperStatus status;

    /** 파싱 실패 코드. 해석하지 않고 저장만 한다 — 코드 목록은 AI 소유·미확정 (design D7). */
    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 상태가 마지막으로 바뀐 시각. 행이 바뀐 시각이 아니다 (계약 `PaperStatusResponse.updatedAt`). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Paper() {
        // JPA
    }

    private Paper(UUID id, UUID ownerId, String filename, Instant now) {
        this.id = id;
        this.ownerId = ownerId;
        this.filename = filename;
        this.fileKey = FILE_KEY_FORMAT.formatted(id);
        this.status = PaperStatus.UPLOAD_PENDING;
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

    /**
     * 파싱 요청 발행 직후의 전이. 발행에 성공한 호출자 한 명만 도달하므로 경쟁이 없다 (design D6).
     *
     * @throws IllegalStateException 현재 상태가 {@code UPLOADED}가 아닐 때
     */
    public void markProcessing(Instant now) {
        if (status != PaperStatus.UPLOADED) {
            throw new IllegalStateException(
                    "UPLOADED에서만 PROCESSING으로 전이할 수 있습니다. 현재 상태=" + status);
        }
        this.status = PaperStatus.PROCESSING;
        this.updatedAt = now;
    }
}
