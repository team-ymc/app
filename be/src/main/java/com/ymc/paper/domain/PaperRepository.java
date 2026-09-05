package com.ymc.paper.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상태는 Paper가 아니라 연결된 {@link Document}가 소유한다 — Paper 자신의 전이는
 * document 연결(CAS) 하나뿐이다.
 */
public interface PaperRepository extends JpaRepository<Paper, UUID> {

    /** 서재 목록. 최근 접근순 — 접근 이력이 없으면 등록 시각을 접근 시각처럼 취급한다 (계약 `GET /api/papers`). */
    @Query("""
            select p from Paper p
             where p.ownerId = :ownerId
             order by coalesce(p.lastAccessedAt, p.createdAt) desc
            """)
    List<Paper> findAllByOwnerIdOrderByRecentAccess(@Param("ownerId") UUID ownerId);

    /**
     * 검증 완료된 Paper를 Document에 연결. 미연결·미만료일 때만 1 row다.
     * 만료 CAS와 같은 Paper 행에서 경쟁하므로 둘 중 먼저 커밋한 전이만 성공한다.
     * updated_at을 함께 갱신한다 — 연결 순간이 이 Paper의 표시 상태가 바뀐 시각이고,
     * bulk UPDATE는 JPA auditing을 우회하기 때문이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Paper p
               set p.documentId = :documentId,
                   p.updatedAt = :now
             where p.id = :paperId
               and p.documentId is null
               and p.expiredAt is null
            """)
    int linkDocument(@Param("paperId") UUID paperId, @Param("documentId") UUID documentId,
            @Param("now") Instant now);

    /** 만료 CAS — 업로드 미완(document 미연결)이고 아직 만료 전일 때만 1 row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Paper p
               set p.expiredAt = :now, p.updatedAt = :now
             where p.id = :paperId
               and p.documentId is null
               and p.expiredAt is null
            """)
    int markExpired(@Param("paperId") UUID paperId, @Param("now") Instant now);

    @Query("select p.id from Paper p where p.documentId = :documentId")
    List<UUID> findIdsByDocumentId(@Param("documentId") UUID documentId);

    /** 정리 스케줄러의 정체 UPLOAD_PENDING 스캔 — document 미연결, 아직 만료 전. */
    @Query("""
            select p.id from Paper p
             where p.documentId is null and p.expiredAt is null and p.createdAt < :cutoff
            """)
    List<UUID> findStaleUploadPendingIds(@Param("cutoff") Instant cutoff);
}
