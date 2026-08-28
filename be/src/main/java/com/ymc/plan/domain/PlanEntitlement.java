package com.ymc.plan.domain;

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

import lombok.Getter;

/**
 * 기본값(Free)에서 벗어난 플랜 권한의 원본 기록. 유효 기간은 [startedAt, endedAt) —
 * 만료는 시각이 지나면 자동 반영되므로 갱신 작업이 없다.
 */
@Getter
@Entity
@Table(
        name = "plan_entitlement",
        indexes = @Index(
                name = "idx_plan_entitlement_lookup",
                columnList = "user_id, plan_code, started_at, ended_at"))
public class PlanEntitlement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, updatable = false, length = 16)
    private PlanCode planCode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false, updatable = false)
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlanEntitlement() {
        // JPA
    }

    private PlanEntitlement(UUID userId, PlanCode planCode, Instant startedAt, Instant endedAt,
            Instant now) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.planCode = Objects.requireNonNull(planCode, "planCode");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.endedAt = Objects.requireNonNull(endedAt, "endedAt");
        this.createdAt = Objects.requireNonNull(now, "now");
        if (!endedAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("endedAt은 startedAt 이후여야 합니다.");
        }
    }

    public static PlanEntitlement grant(UUID userId, PlanCode planCode, Instant startedAt,
            Instant endedAt, Instant now) {
        return new PlanEntitlement(userId, planCode, startedAt, endedAt, now);
    }
}
