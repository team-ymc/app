package com.ymc.paper.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 선행지식 하이라이트 1개 = 1행. 한 text block 안의 범위이며 offset은 UTF-16 code unit, start 포함·end 제외다.
 */
@Getter
@Entity
@Table(
        name = "document_prerequisite_highlight",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_prerequisite_highlight",
                columnNames = {"document_id", "highlight_id"}))
public class DocumentPrerequisiteHighlight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    /** 컴파일 사이드카가 부여한 식별자 (예: prerequisite-0001). Document 안에서만 유일하다. */
    @Column(name = "highlight_id", nullable = false, updatable = false, length = 64)
    private String highlightId;

    @Column(name = "block_id", nullable = false, updatable = false)
    private String blockId;

    @Column(name = "start_offset", nullable = false, updatable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false, updatable = false)
    private int endOffset;

    /** 해당 범위의 원문. 설명 캐시 키와 팝오버 제목에 쓴다. */
    @Column(name = "text", nullable = false, updatable = false, columnDefinition = "text")
    private String text;

    protected DocumentPrerequisiteHighlight() {
        // JPA
    }

    private DocumentPrerequisiteHighlight(
            UUID documentId, String highlightId, String blockId, int startOffset, int endOffset, String text) {
        this.documentId = documentId;
        this.highlightId = highlightId;
        this.blockId = blockId;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.text = text;
    }

    public static DocumentPrerequisiteHighlight of(
            UUID documentId, String highlightId, String blockId, int startOffset, int endOffset, String text) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(highlightId, "highlightId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(text, "text");
        return new DocumentPrerequisiteHighlight(documentId, highlightId, blockId, startOffset, endOffset, text);
    }
}
