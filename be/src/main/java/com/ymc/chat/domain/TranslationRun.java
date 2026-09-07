package com.ymc.chat.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 인라인 번역 실행 한 건. GENERATING으로 시작해 완료 시 translation이 1회 채워진다 (delta는 저장하지 않는다).
 * 종결 전이는 relay 정상 완료·timeout·정체 정리가 경쟁하므로 {@link TranslationRunRepository}의 조건부 UPDATE로만 한다.
 */
@Getter
@Entity
@Table(
        name = "translation_run",
        indexes = @Index(name = "ix_translation_run_owner_status", columnList = "owner_id, status"))
public class TranslationRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TranslationRunStatus status;

    /** 요청 selection(start/end anchor) 그대로 — 입력 맥락 추적용. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection", nullable = false, updatable = false)
    private JsonNode selection;

    /** COMPLETED에만. 최종 한국어 번역 Markdown. */
    @Column(name = "translation", columnDefinition = "text")
    private String translation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TranslationRun() {
        // JPA
    }

    private TranslationRun(UUID ownerId, UUID paperId, JsonNode selection, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.paperId = Objects.requireNonNull(paperId, "paperId");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.status = TranslationRunStatus.GENERATING;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public static TranslationRun start(UUID ownerId, UUID paperId, JsonNode selection, Instant now) {
        return new TranslationRun(ownerId, paperId, selection, now);
    }
}
