package com.ymc.paper.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * 상태 전이는 전부 조건부 UPDATE(CAS) — 변경 row 수가 1일 때만 후속 동작. Paper CAS와 같은 관용구다.
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByChecksumSha256(String checksumSha256);

    Optional<Document> findByRequestPaperId(UUID requestPaperId);

    /** 연결·종결 직렬화용 잠금 조회 — 종결 UPDATE와 이 잠금이 같은 행에서 만난다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.checksumSha256 = :checksumSha256")
    Optional<Document> findWithLockByChecksumSha256(@Param("checksumSha256") String checksumSha256);

    /**
     * checksum 유일성을 지키는 생성. unique 위반 예외에 의존하면 PostgreSQL이 트랜잭션을
     * abort시켜 같은 Tx에서 기존-Document 경로로 전환할 수 없으므로 ON CONFLICT를 쓴다.
     *
     * @return 1이면 이 호출이 생성함, 0이면 같은 checksum이 이미 있음
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into document
                (id, checksum_sha256, file_key, status, error_code, request_paper_id, created_at, updated_at)
            values (:id, :checksum, :fileKey, 'UPLOADED', null, :requestPaperId, :now, :now)
            on conflict (checksum_sha256) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("checksum") String checksum,
            @Param("fileKey") String fileKey,
            @Param("requestPaperId") UUID requestPaperId,
            @Param("now") Instant now);

    /** 파싱 시작 권한 선점. 승자 1명만 1을 받고, 승자만 발행한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.status = com.ymc.paper.domain.DocumentStatus.PROCESSING,
                   d.updatedAt = :now
             where d.id = :id
               and d.status = com.ymc.paper.domain.DocumentStatus.UPLOADED
            """)
    int markProcessing(@Param("id") UUID id, @Param("now") Instant now);

    /** 발행 실패 시 선점 반납 — 다음 complete가 다시 선점(구제)할 수 있게 한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.status = com.ymc.paper.domain.DocumentStatus.UPLOADED,
                   d.updatedAt = :now
             where d.id = :id
               and d.status = com.ymc.paper.domain.DocumentStatus.PROCESSING
            """)
    int revertToUploaded(@Param("id") UUID id, @Param("now") Instant now);

    /** 결과 수신 전이. UPLOADED 포함 — PROCESSING 커밋 전에 결과가 도착하는 경합을 흡수한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.status = :terminal,
                   d.errorCode = :errorCode,
                   d.updatedAt = :now
             where d.id = :id
               and d.status in (com.ymc.paper.domain.DocumentStatus.UPLOADED,
                                com.ymc.paper.domain.DocumentStatus.PROCESSING)
            """)
    int markParsed(
            @Param("id") UUID id,
            @Param("terminal") DocumentStatus terminal,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now);

    /** 정리 스케줄러의 정체 UPLOADED·PROCESSING 스캔. */
    @Query("select d.id from Document d where d.status in :statuses and d.updatedAt < :cutoff")
    List<UUID> findStaleIds(@Param("statuses") Collection<DocumentStatus> statuses,
            @Param("cutoff") Instant cutoff);

    /** 테스트 전용 — 전이 시각을 과거로 되돌려 스캔 기준 통과를 재현한다. */
    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.updatedAt = :at where d.id = :id")
    void backdateUpdatedAt(@Param("id") UUID id, @Param("at") Instant at);
}
