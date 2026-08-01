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
 *
 * <p>title·lastMessageAt은 목록 조회용 비정규화(YMC-260 설계 §2).
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

    /** 목록 표시용 — 첫 user 질문의 앞 120자 (코드포인트 기준). 생성 시 1회 저장, 불변. */
    @Column(name = "title", nullable = false, length = 120, updatable = false)
    private String title;

    /** 마지막 메시지 저장 시각 — 목록 정렬 키. start 트랜잭션(세션 행 잠금 상태)에서 갱신된다. */
    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    protected ChatSession() {
        // JPA
    }

    private static final int TITLE_MAX_CODE_POINTS = 120;

    private ChatSession(UUID ownerId, UUID paperId, String firstQuestion, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.paperId = paperId;
        this.title = truncateTitle(firstQuestion);
        this.createdAt = now;
        this.lastMessageAt = now;
    }

    /** 새 세션. 첫 질문에서 sessionId 없이 요청이 오면 만든다. */
    public static ChatSession open(UUID ownerId, UUID paperId, String firstQuestion, Instant now) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(firstQuestion, "firstQuestion");
        Objects.requireNonNull(now, "now");
        return new ChatSession(ownerId, paperId, firstQuestion, now);
    }

    /** 메시지 쌍 저장 시 호출 — 목록 정렬 키 갱신. */
    public void recordActivity(Instant now) {
        this.lastMessageAt = now;
    }

    /** surrogate pair를 반 자르지 않도록 코드포인트 기준으로 절단한다. */
    private static String truncateTitle(String firstQuestion) {
        if (firstQuestion.codePointCount(0, firstQuestion.length()) <= TITLE_MAX_CODE_POINTS) {
            return firstQuestion;
        }
        return firstQuestion.substring(
                0, firstQuestion.offsetByCodePoints(0, TITLE_MAX_CODE_POINTS));
    }
}
