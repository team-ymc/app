package com.ymc.plan.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    Optional<UsageRecord> findByUsageTypeAndSourceId(UsageType usageType, UUID sourceId);

    long countByBucketIdAndStatusIn(UUID bucketId, Collection<UsageRecordStatus> statuses);

    /** 정산 CAS — RESERVED일 때만 1 row. 중복 신호는 0 row로 걸러진다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UsageRecord r
               set r.status = :to, r.estimatedCostUsd = :cost, r.updatedAt = :now
             where r.usageType = :usageType and r.sourceId = :sourceId
               and r.status = com.ymc.plan.domain.UsageRecordStatus.RESERVED
            """)
    int settleOne(@Param("usageType") UsageType usageType, @Param("sourceId") UUID sourceId,
            @Param("to") UsageRecordStatus to, @Param("cost") BigDecimal cost,
            @Param("now") Instant now);

    /** 문서 종결 정산 — 연결된 Paper 전체를 한 번에. 이미 정산된 row는 조건이 거른다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UsageRecord r
               set r.status = :to, r.updatedAt = :now
             where r.usageType = :usageType and r.sourceId in :sourceIds
               and r.status = com.ymc.plan.domain.UsageRecordStatus.RESERVED
            """)
    int settleAll(@Param("usageType") UsageType usageType,
            @Param("sourceIds") Collection<UUID> sourceIds,
            @Param("to") UsageRecordStatus to, @Param("now") Instant now);
}
