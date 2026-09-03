package com.ymc.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    Optional<ChatMessage> findByClientMessageIdAndRole(UUID clientMessageId, ChatMessageRole role);

    boolean existsBySessionIdAndStatus(UUID sessionId, ChatMessageStatus status);

    /** 소유자의 GENERATING assistant 수 = 활성 세션 수. 논리 삭제된 세션도 센다. */
    @Query("""
            select count(m) from ChatMessage m
            where m.session.ownerId = :ownerId and m.role = :role and m.status = :status
            """)
    long countBySessionOwnerIdAndRoleAndStatus(
            UUID ownerId, ChatMessageRole role, ChatMessageStatus status);

    /**
     * {@code GENERATING → COMPLETED} 조건부 전이 + 최종 content 1회 저장.
     *
     * @return 1이면 이 호출이 전이의 주인. 0이면 이미 다른 경로(실패 처리 등)가 전이시켰다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update ChatMessage m
               set m.status = com.ymc.chat.domain.ChatMessageStatus.COMPLETED,
                   m.content = :content,
                   m.completedAt = :now
             where m.id = :id
               and m.status = com.ymc.chat.domain.ChatMessageStatus.GENERATING
            """)
    int markCompleted(UUID id, String content, Instant now);

    /** {@code GENERATING → FAILED} 조건부 전이. partial content는 저장하지 않는다. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update ChatMessage m
               set m.status = com.ymc.chat.domain.ChatMessageStatus.FAILED,
                   m.completedAt = :now
             where m.id = :id
               and m.status = com.ymc.chat.domain.ChatMessageStatus.GENERATING
            """)
    int markFailed(UUID id, Instant now);

    /** 세션의 현재 최대 seq. start 트랜잭션이 세션 행을 잠근 상태에서만 호출한다 — 경쟁 없음. */
    @Query("select max(m.seq) from ChatMessage m where m.session.id = :sessionId")
    Optional<Integer> findMaxSeqBySessionId(UUID sessionId);

    /** 히스토리 조회 (계약 listChatSessionMessages). 정렬 키는 seq — ix_chat_message_session_seq. */
    List<ChatMessage> findAllBySessionIdOrderBySeqAsc(UUID sessionId);

    /** 정리 스케줄러의 정체 GENERATING 스캔. */
    List<ChatMessage> findAllByRoleAndStatusAndCreatedAtBefore(
            ChatMessageRole role, ChatMessageStatus status, Instant cutoff);
}
