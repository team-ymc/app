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
 * 이미지·차트 asset 1개 = 1행. URL은 저장하지 않는다 — 조회 시 s3Key로 presigned GET을 발급한다.
 */
@Getter
@Entity
@Table(
        name = "paper_content_asset",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_paper_content_asset",
                columnNames = {"paper_id", "asset_key"}))
public class PaperContentAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    /** 계약 assets 맵의 키 (예: image_0). 블록 content.assetKey가 이 값을 가리킨다. */
    @Column(name = "asset_key", nullable = false, updatable = false)
    private String assetKey;

    @Column(name = "s3_key", nullable = false, updatable = false)
    private String s3Key;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    protected PaperContentAsset() {
        // JPA
    }

    private PaperContentAsset(UUID paperId, String assetKey, String s3Key, String mediaType) {
        this.paperId = paperId;
        this.assetKey = assetKey;
        this.s3Key = s3Key;
        this.mediaType = mediaType;
    }

    public static PaperContentAsset of(UUID paperId, String assetKey, String s3Key, String mediaType) {
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(assetKey, "assetKey");
        Objects.requireNonNull(s3Key, "s3Key");
        Objects.requireNonNull(mediaType, "mediaType");
        return new PaperContentAsset(paperId, assetKey, s3Key, mediaType);
    }
}
