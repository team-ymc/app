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

    /**
     * 환불된 기록을 주어진 버킷으로 옮기며 다시 예약한다. RELEASED일 때만 1 row.
     * bucket_id는 엔티티 저장으로는 바뀌지 않게 매핑돼 있어 네이티브 UPDATE로 옮긴다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update usage_record
               set status = 'RESERVED', bucket_id = :bucketId, updated_at = :now
             where usage_type = :usageType
               and source_id = :sourceId
               and status = 'RELEASED'
            """, nativeQuery = true)
    int reReserve(@Param("usageType") String usageType, @Param("sourceId") UUID sourceId,
            @Param("bucketId") UUID bucketId, @Param("now") Instant now);
}
