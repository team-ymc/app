package com.ymc.plan.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;
import com.ymc.plan.domain.UsageBucket;
import com.ymc.plan.domain.UsageBucketRepository;
import com.ymc.plan.domain.UsageRecord;
import com.ymc.plan.domain.UsageRecordRepository;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.infra.PlanProperties;

import lombok.RequiredArgsConstructor;

/**
 * 사용량 예약·확정·해제. 모든 쓰기가 호출부의 시작·종결 트랜잭션에 합류해야 하므로
 * MANDATORY다 — 단독 호출은 설계 위반이고 즉시 예외로 드러난다.
 */
@Service
@RequiredArgsConstructor
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private static final List<UsageRecordStatus> ACTIVE =
            List.of(UsageRecordStatus.RESERVED, UsageRecordStatus.CONFIRMED);

    private final PlanService planService;
    private final PlanProperties properties;
    private final UsageBucketRepository bucketRepository;
    private final UsageRecordRepository recordRepository;

    /**
     * 한도 판정과 1회 예약을 원자적으로 처리한다. 유한 정책은 버킷 행 잠금으로 동시
     * 예약을 직렬화하고, UNLIMITED는 잠금 없이 기록만 남긴다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID userId, UsageType usageType, UUID sourceId) {
        Instant now = Instant.now();
        Optional<UsageRecord> existing =
                recordRepository.findByUsageTypeAndSourceId(usageType, sourceId);
        if (existing.isPresent()) {
            if (existing.get().getStatus() == UsageRecordStatus.RELEASED) {
                throw new IllegalStateException(
                        "해제된 실행의 재예약: " + usageType + "/" + sourceId);
            }
            return; // 같은 실행의 재전달 — 이미 예약·확정됨
        }
        PlanCode plan = planService.effectivePlan(userId, now);
        PlanProperties.Policy policy = properties.policyOf(plan, usageType);
        Instant bucketStart = BucketPeriod.startOf(now);
        bucketRepository.insertIfAbsent(UUID.randomUUID(), userId,
                usageType.name(), plan.name(), bucketStart, now);

        UsageBucket bucket;
        if (policy.mode() == PolicyMode.MONTHLY) {
            bucket = bucketRepository
                    .findWithLock(userId, usageType, plan, bucketStart).orElseThrow();
            long used = recordRepository.countByBucketIdAndStatusIn(bucket.getId(), ACTIVE);
            if (used >= policy.limit()) {
                throw limitExceeded(usageType);
            }
        } else {
            bucket = bucketRepository
                    .findByUserIdAndUsageTypeAndPlanCodeAndBucketStart(
                            userId, usageType, plan, bucketStart).orElseThrow();
        }
        // 동시 같은 sourceId는 유니크 제약이 최후 방어선이다 — flush로 위반을 호출 지점에서
        // 동기적으로 드러내 호출부 트랜잭션의 기존 방어가 받게 한다
        recordRepository.saveAndFlush(UsageRecord.reserve(bucket.getId(), usageType, sourceId, now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(UsageType usageType, UUID sourceId, BigDecimal estimatedCostUsd) {
        settle(usageType, sourceId, UsageRecordStatus.CONFIRMED, estimatedCostUsd);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UsageType usageType, UUID sourceId) {
        settle(usageType, sourceId, UsageRecordStatus.RELEASED, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmAll(UsageType usageType, List<UUID> sourceIds) {
        if (!sourceIds.isEmpty()) {
            recordRepository.settleAll(usageType, sourceIds,
                    UsageRecordStatus.CONFIRMED, Instant.now());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseAll(UsageType usageType, List<UUID> sourceIds) {
        if (!sourceIds.isEmpty()) {
            recordRepository.settleAll(usageType, sourceIds,
                    UsageRecordStatus.RELEASED, Instant.now());
        }
    }

    private void settle(UsageType usageType, UUID sourceId, UsageRecordStatus to,
            BigDecimal cost) {
        if (recordRepository.settleOne(usageType, sourceId, to, cost, Instant.now()) == 1) {
            return;
        }
        if (recordRepository.findByUsageTypeAndSourceId(usageType, sourceId).isPresent()) {
            log.debug("이미 정산된 실행의 중복 신호 무시: {}/{}", usageType, sourceId);
        } else {
            log.warn("예약 없는 정산 신호: {}/{}/{}", usageType, sourceId, to);
        }
    }

    private ApiException limitExceeded(UsageType usageType) {
        return switch (usageType) {
            case AI_QUERY -> new ApiException(ErrorCode.CHAT_USAGE_LIMIT_EXCEEDED,
                    "이번 달 AI 질의 횟수를 모두 사용했습니다.");
            case PAPER_REGISTRATION -> new ApiException(ErrorCode.PAPER_USAGE_LIMIT_EXCEEDED,
                    "이번 달 문서 등록 횟수를 모두 사용했습니다.");
        };
    }
}
