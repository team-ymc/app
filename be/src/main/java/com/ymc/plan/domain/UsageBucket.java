package com.ymc.plan.domain;

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
 * 월 버킷 — 예약 직렬화의 잠금 앵커. 집계 카운터를 두지 않는다.
 */
@Getter
@Entity
@Table(
        name = "usage_bucket",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_usage_bucket",
                columnNames = {"user_id", "usage_type", "plan_code", "bucket_start"}))
public class UsageBucket {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_type", nullable = false, updatable = false, length = 32)
    private UsageType usageType;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, updatable = false, length = 16)
    private PlanCode planCode;

    @Column(name = "bucket_start", nullable = false, updatable = false)
    private Instant bucketStart;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UsageBucket() {
        // JPA
    }

    private UsageBucket(UUID userId, UsageType usageType, PlanCode planCode, Instant bucketStart,
            Instant now) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.usageType = Objects.requireNonNull(usageType, "usageType");
        this.planCode = Objects.requireNonNull(planCode, "planCode");
        this.bucketStart = Objects.requireNonNull(bucketStart, "bucketStart");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public static UsageBucket open(UUID userId, UsageType usageType, PlanCode planCode,
            Instant bucketStart, Instant now) {
        return new UsageBucket(userId, usageType, planCode, bucketStart, now);
    }
}
