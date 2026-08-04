package com.ymc.paper.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 본문 블록 1개 = 1행. 수식 tex·표 html은 적재 시점에 content에 인라인돼 있다.
 * label은 파서 분류 문자열 그대로 저장한다 — enum은 계약이 소유하고, 새 label은 계약 PR부터다.
 * content는 계약 응답의 content 필드 형태({"format":"text","text":...} 등)로 저장해 조회 시 그대로 내보낸다.
 */
@Getter
@Entity
@Table(
        name = "paper_content_block",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_paper_content_block",
                columnNames = {"paper_id", "block_id"}),
        indexes = @Index(
                name = "ix_paper_content_block_order",
                columnList = "paper_id, global_order"))
public class PaperContentBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    /** 파서가 부여한 안정 블록 id (예: p0002-b0006). FE 선택 anchor·DOM id로 쓰인다. */
    @Column(name = "block_id", nullable = false, updatable = false)
    private String blockId;

    @Column(name = "global_order", nullable = false, updatable = false)
    private int globalOrder;

    @Column(name = "label", nullable = false, length = 32)
    private String label;

    @Column(name = "heading_level")
    private Integer headingLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "section_path", nullable = false)
    private List<String> sectionPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false)
    private JsonNode content;

    protected PaperContentBlock() {
        // JPA
    }

    private PaperContentBlock(UUID paperId, String blockId, int globalOrder, String label,
            Integer headingLevel, List<String> sectionPath, JsonNode content) {
        this.paperId = paperId;
        this.blockId = blockId;
        this.globalOrder = globalOrder;
        this.label = label;
        this.headingLevel = headingLevel;
        this.sectionPath = sectionPath;
        this.content = content;
    }

    public static PaperContentBlock of(UUID paperId, String blockId, int globalOrder, String label,
            Integer headingLevel, List<String> sectionPath, JsonNode content) {
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(sectionPath, "sectionPath");
        Objects.requireNonNull(content, "content");
        return new PaperContentBlock(paperId, blockId, globalOrder, label, headingLevel, sectionPath, content);
    }
}
