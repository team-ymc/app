package com.ymc.plan.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PlanEntitlement;
import com.ymc.plan.domain.PlanEntitlementRepository;

import lombok.RequiredArgsConstructor;

/**
 * 요청 시각 기준 유효 플랜 판정. 상태를 바꾸지 않으므로 잠금이 없고, 만료는 시각이
 * 지나면 자동 반영된다.
 */
@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanEntitlementRepository entitlementRepository;

    public PlanCode effectivePlan(UUID userId, Instant at) {
        boolean pro = entitlementRepository
                .existsByUserIdAndPlanCodeAndStartedAtLessThanEqualAndEndedAtGreaterThan(
                        userId, PlanCode.PRO, at, at);
        return pro ? PlanCode.PRO : PlanCode.FREE;
    }

    /** 현재 유효한 Pro 권한 중 가장 늦은 만료 시각. 없으면 empty. */
    public Optional<Instant> proExpiresAt(UUID userId, Instant at) {
        return entitlementRepository
                .findAllByUserIdAndPlanCodeAndStartedAtLessThanEqualAndEndedAtGreaterThan(
                        userId, PlanCode.PRO, at, at)
                .stream()
                .map(PlanEntitlement::getEndedAt)
                .max(Instant::compareTo);
    }
}
