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
 * 적재된 논문 본문의 헤더 — 행 존재 자체가 "적재 완료"의 기준이다.
 * paper와는 ID로만 연결한다.
 */
@Getter
@Entity
@Table(name = "paper_content")
public class PaperContent {

    @Id
    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    /** doc_title 블록의 텍스트. 파서가 제목을 못 찾은 문서는 null (계약 title). */
    @Column(name = "title")
    private String title;

    /** 파서 frontend projection의 schema_version. */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected PaperContent() {
        // JPA
    }

    private PaperContent(UUID paperId, String title, int schemaVersion, Instant ingestedAt) {
        this.paperId = paperId;
        this.title = title;
        this.schemaVersion = schemaVersion;
        this.ingestedAt = ingestedAt;
    }

    public static PaperContent of(UUID paperId, String title, int schemaVersion, Instant now) {
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(now, "now");
        return new PaperContent(paperId, title, schemaVersion, now);
    }
}
