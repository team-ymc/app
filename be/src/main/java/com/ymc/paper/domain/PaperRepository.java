package com.ymc.paper.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 경쟁이 실재하는 두 전이는 조건부 UPDATE(CAS)로 판정한다 — 변경된 row가 1일 때만 후속 동작을 진행한다 (design D2).
 * 나머지 전이·불변식은 {@link Paper}의 메서드가 갖는다.
 */
public interface PaperRepository extends JpaRepository<Paper, UUID> {

    /**
     * 파일명 중복 판정. 상태를 가리지 않는다 — 업로드에 실패한 {@code UPLOAD_PENDING} 레코드도
     * 중복으로 걸린다 (계약 주의사항, MVP는 같은 파일명 재업로드 미지원).
     */
    boolean existsByOwnerIdAndFilename(UUID ownerId, String filename);

    /** 서재 목록. 고정 owner 전체 (정렬·페이징 없음, 계약대로 단순 전체). */
    List<Paper> findAllByOwnerId(UUID ownerId);

    /**
     * complete 수신 시의 {@code UPLOAD_PENDING → UPLOADED}. 동시 complete 호출 중 한 건만 1을 받는다.
     *
     * @return 변경된 row 수 (1이면 이 호출이 전이의 주인 — 파싱 요청을 발행한다)
     */
    // flushAutomatically : UPDATE 전, 영속성 컨텍스트 변경사항 flush
    // clearAutomatically : Update 후, 1차 캐시 비움. 과거 데이터 제거하여 새로운 데이터 read해옴
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Paper p
               set p.status = com.ymc.paper.domain.PaperStatus.UPLOADED,
                   p.updatedAt = :now
             where p.id = :id
               and p.status = com.ymc.paper.domain.PaperStatus.UPLOAD_PENDING
            """)
    int markUploaded(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * 결과 수신 시의 {@code PROCESSING → COMPLETED | FAILED}. 중복 수신·이미 terminal이면 0을 받는다.
     *
     * @param terminal  {@code COMPLETED} 또는 {@code FAILED}
     * @param errorCode 실패 코드. {@code COMPLETED}면 null
     * @return 변경된 row 수 (0이면 이미 전이됐거나 PROCESSING이 아님 — 경고 로그 후 소비)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Paper p
               set p.status = :terminal,
                   p.errorCode = :errorCode,
                   p.updatedAt = :now
             where p.id = :id
               and p.status = com.ymc.paper.domain.PaperStatus.PROCESSING
            """)
    int markParsed(
            @Param("id") UUID id,
            @Param("terminal") PaperStatus terminal,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now);
}
