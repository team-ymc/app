package com.ymc.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TranslationRunRepository extends JpaRepository<TranslationRun, UUID> {

    /** 사용자당 동시 번역 1개 검사. users 행 잠금 아래에서만 호출한다. */
    boolean existsByOwnerIdAndStatus(UUID ownerId, TranslationRunStatus status);

    /** GENERATING → COMPLETED 조건부 전이 + translation 1회 저장. 1이면 이 호출이 전이의 주인. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update TranslationRun r
               set r.status = com.ymc.chat.domain.TranslationRunStatus.COMPLETED,
                   r.translation = :translation,
                   r.completedAt = :now
             where r.id = :id
               and r.status = com.ymc.chat.domain.TranslationRunStatus.GENERATING
            """)
    int markCompleted(UUID id, String translation, Instant now);

    /** GENERATING → FAILED 조건부 전이. partial은 저장하지 않는다. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update TranslationRun r
               set r.status = com.ymc.chat.domain.TranslationRunStatus.FAILED,
                   r.completedAt = :now
             where r.id = :id
               and r.status = com.ymc.chat.domain.TranslationRunStatus.GENERATING
            """)
    int markFailed(UUID id, Instant now);

    /** 정리 스케줄러의 정체 GENERATING 스캔. */
    List<TranslationRun> findAllByStatusAndCreatedAtBefore(TranslationRunStatus status, Instant cutoff);
}
