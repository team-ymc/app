package com.ymc.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    /** 목록 조회 (계약 listChatSessions). 삭제된 세션은 제외. 정렬 키는 비정규화된 lastMessageAt. */
    List<ChatSession> findAllByOwnerIdAndPaperIdAndDeletedAtIsNullOrderByLastMessageAtDesc(
            UUID ownerId, UUID paperId);

    /**
     * 세션 행을 PESSIMISTIC_WRITE로 잠근다 (SELECT ... FOR UPDATE).
     *
     * <p>"세션당 동시 실행 1개"는 check-then-insert 조회만으로는 경쟁을 막지 못한다 —
     * 같은 세션의 시작 요청을 이 잠금으로 직렬화한다 (설계 §3). 트랜잭션이 짧고
     * (스트리밍 시작 전 commit) 단일 행 잠금이라 데드락 여지가 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> findWithLockById(UUID id);

    /** 논문 삭제에 딸려 소속 세션을 논리 삭제한다. 살아 있는 세션만 건드린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChatSession s
               set s.deletedAt = :now
             where s.paperId = :paperId
               and s.deletedAt is null
            """)
    int markDeletedByPaperId(@Param("paperId") UUID paperId, @Param("now") Instant now);
}
