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
 * document와는 ID로만 연결한다. 같은 document를 참조하는 모든 paper가 이 행을 공유한다.
 */
@Getter
@Entity
@Table(name = "document_content")
public class DocumentContent {

    @Id
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    /** doc_title 블록의 텍스트. 파서가 제목을 못 찾은 문서는 null (계약 title). */
    @Column(name = "title")
    private String title;

    /** 파서 frontend projection의 schema_version. */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected DocumentContent() {
        // JPA
    }

    private DocumentContent(UUID documentId, String title, int schemaVersion, Instant ingestedAt) {
        this.documentId = documentId;
        this.title = title;
        this.schemaVersion = schemaVersion;
        this.ingestedAt = ingestedAt;
    }

    public static DocumentContent of(UUID documentId, String title, int schemaVersion, Instant now) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(now, "now");
        return new DocumentContent(documentId, title, schemaVersion, now);
    }
}
