package com.ymc.chat.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

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
}
