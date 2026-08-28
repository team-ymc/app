package com.ymc.plan.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.plan.api.dto.PlanUsageResponse;
import com.ymc.plan.api.dto.PlanUsageResponse.PlanFeatureUsage;
import com.ymc.plan.api.dto.PlanUsageResponse.UsageLimit;
import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;
import com.ymc.plan.domain.UsageBucketRepository;
import com.ymc.plan.domain.UsageRecordRepository;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.infra.PlanProperties;

import lombok.RequiredArgsConstructor;

/** 현재 플랜·사용량 조회. 읽기만 하며 버킷을 만들지 않는다 — 없으면 0회. */
@Service
@RequiredArgsConstructor
public class PlanQueryService {

    private static final List<UsageRecordStatus> ACTIVE =
            List.of(UsageRecordStatus.RESERVED, UsageRecordStatus.CONFIRMED);

    private final PlanService planService;
    private final PlanProperties properties;
    private final UsageBucketRepository bucketRepository;
    private final UsageRecordRepository recordRepository;

    @Transactional(readOnly = true)
    public PlanUsageResponse myPlan(UUID userId) {
        Instant now = Instant.now();
        PlanCode plan = planService.effectivePlan(userId, now);
        Instant expiresAt = plan == PlanCode.PRO
                ? planService.proExpiresAt(userId, now).orElse(null) : null;
        return new PlanUsageResponse(plan, expiresAt, new PlanFeatureUsage(
                usageOf(userId, plan, UsageType.AI_QUERY, now),
                usageOf(userId, plan, UsageType.PAPER_REGISTRATION, now)));
    }

    private UsageLimit usageOf(UUID userId, PlanCode plan, UsageType usageType, Instant now) {
        PlanProperties.Policy policy = properties.policyOf(plan, usageType);
        if (policy.mode() == PolicyMode.UNLIMITED) {
            return UsageLimit.unlimited();
        }
        long used = bucketRepository
                .findByUserIdAndUsageTypeAndPlanCodeAndBucketStart(
                        userId, usageType, plan, BucketPeriod.startOf(now))
                .map(b -> recordRepository.countByBucketIdAndStatusIn(b.getId(), ACTIVE))
                .orElse(0L);
        return new UsageLimit(PolicyMode.MONTHLY, policy.limit(), used,
                Math.max(0, policy.limit() - used), BucketPeriod.nextResetAfter(now));
    }
}
