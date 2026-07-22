package com.ymc.chat.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    Optional<ChatMessage> findByClientMessageIdAndRole(UUID clientMessageId, ChatMessageRole role);

    boolean existsBySessionIdAndStatus(UUID sessionId, ChatMessageStatus status);

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
}
