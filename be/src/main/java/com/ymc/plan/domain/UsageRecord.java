package com.ymc.plan.domain;

import java.math.BigDecimal;
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
 * 실행별 사용량 원장. 해제해도 RELEASED로 남긴다 — 감사와 중복 신호 판정.
 */
@Getter
@Entity
@Table(
        name = "usage_record",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_usage_record_type_source",
                columnNames = {"usage_type", "source_id"}),
        indexes = @Index(
                name = "idx_usage_record_bucket",
                columnList = "bucket_id"))
public class UsageRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "bucket_id", nullable = false, updatable = false)
    private UUID bucketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_type", nullable = false, updatable = false, length = 32)
    private UsageType usageType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    /** 컬럼은 backfill 전 legacy 행 때문에 nullable이지만 새 행은 생성자가 non-null을 강제한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 32, updatable = false)
    private UsageSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UsageRecordStatus status;

    @Column(name = "estimated_cost_usd", precision = 14, scale = 8)
    private BigDecimal estimatedCostUsd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UsageRecord() {
        // JPA
    }

    private UsageRecord(UUID bucketId, UsageType usageType, UUID sourceId,
            UsageSourceType sourceType, Instant now) {
        this.id = UUID.randomUUID();
        this.bucketId = Objects.requireNonNull(bucketId, "bucketId");
        this.usageType = Objects.requireNonNull(usageType, "usageType");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.status = UsageRecordStatus.RESERVED;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public static UsageRecord reserve(UUID bucketId, UsageType usageType, UUID sourceId,
            UsageSourceType sourceType, Instant now) {
        return new UsageRecord(bucketId, usageType, sourceId, sourceType, now);
    }
}
