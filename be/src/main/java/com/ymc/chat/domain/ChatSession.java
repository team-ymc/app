package com.ymc.chat.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * 논문 하나에 대한 채팅 세션. id는 BE가 insert 전에 만들며 AI thread_id로 그대로 전달된다
 * (계약 x-upstream-event-mapping). 세션은 생성 시 paperId에 고정된다.
 *
 * <p>owner·paper는 다른 컨텍스트라 ID로만 참조한다 (be/CLAUDE.md 의존성 규칙).
 */
@Getter
@Entity
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatSession() {
        // JPA
    }

    private ChatSession(UUID ownerId, UUID paperId, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.paperId = paperId;
        this.createdAt = now;
    }

    /** 새 세션. 첫 질문에서 sessionId 없이 요청이 오면 만든다. */
    public static ChatSession open(UUID ownerId, UUID paperId, Instant now) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(now, "now");
        return new ChatSession(ownerId, paperId, now);
    }
}
