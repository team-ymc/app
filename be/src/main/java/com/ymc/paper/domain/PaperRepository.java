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

    /**
     * 파일명 중복 판정. 상태를 가리지 않는다 — 업로드에 실패한 {@code UPLOAD_PENDING} 레코드도
     * 중복으로 걸린다 (계약 주의사항, MVP는 같은 파일명 재업로드 미지원).
     */
    boolean existsByOwnerIdAndFilename(UUID ownerId, String filename);

    /** 서재 목록. 최근 접근순 — 접근 이력이 없으면 등록 시각을 접근 시각처럼 취급한다 (계약 `GET /api/papers`). */
    @Query("""
            select p from Paper p
             where p.ownerId = :ownerId
             order by coalesce(p.lastAccessedAt, p.createdAt) desc
            """)
    List<Paper> findAllByOwnerIdOrderByRecentAccess(@Param("ownerId") UUID ownerId);

    /**
     * 검증 완료된 Paper를 Document에 연결. document_id가 null일 때만 1 row다.
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
            """)
    int linkDocument(@Param("paperId") UUID paperId, @Param("documentId") UUID documentId,
            @Param("now") Instant now);
}
