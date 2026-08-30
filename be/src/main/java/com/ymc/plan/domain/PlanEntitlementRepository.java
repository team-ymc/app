package com.ymc.plan.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, UUID> {

    /** 유효 권한 존재 판정 — [startedAt, endedAt) 기간 조건. */
    boolean existsByUserIdAndPlanCodeAndStartedAtLessThanEqualAndEndedAtGreaterThan(
            UUID userId, PlanCode planCode, Instant at, Instant sameAt);

    List<PlanEntitlement> findAllByUserIdAndPlanCodeAndStartedAtLessThanEqualAndEndedAtGreaterThan(
            UUID userId, PlanCode planCode, Instant at, Instant sameAt);
}
