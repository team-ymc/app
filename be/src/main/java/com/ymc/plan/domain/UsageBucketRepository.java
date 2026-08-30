package com.ymc.plan.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageBucketRepository extends JpaRepository<UsageBucket, UUID> {

    /** 버킷 확보 — 있으면 무시. 잠금 전에 행 존재를 보장한다. */
    @Modifying
    @Query(value = """
            insert into usage_bucket (id, user_id, usage_type, plan_code, bucket_start, created_at)
            values (:id, :userId, :usageType, :planCode, :bucketStart, :now)
            on conflict on constraint uk_usage_bucket do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
            @Param("usageType") String usageType, @Param("planCode") String planCode,
            @Param("bucketStart") Instant bucketStart, @Param("now") Instant now);

    /** 예약 직렬화 지점 — SELECT ... FOR UPDATE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from UsageBucket b
             where b.userId = :userId and b.usageType = :usageType
               and b.planCode = :planCode and b.bucketStart = :bucketStart
            """)
    Optional<UsageBucket> findWithLock(@Param("userId") UUID userId,
            @Param("usageType") UsageType usageType, @Param("planCode") PlanCode planCode,
            @Param("bucketStart") Instant bucketStart);

    Optional<UsageBucket> findByUserIdAndUsageTypeAndPlanCodeAndBucketStart(
            UUID userId, UsageType usageType, PlanCode planCode, Instant bucketStart);
}
