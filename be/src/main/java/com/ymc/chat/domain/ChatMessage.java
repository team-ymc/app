package com.ymc.chat.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 세션 안의 메시지 한 건. user 질문은 저장 즉시 COMPLETED, assistant 답변은 GENERATING으로
 * 시작해 완료 시 content가 1회 채워진다 (ADR-004 — delta는 저장하지 않는다).
 *
 * <p>{@code GENERATING → COMPLETED/FAILED}는 relay 정상 완료와 timeout이 경쟁하므로 엔티티
 * 메서드가 아닌 {@link ChatMessageRepository}의 조건부 UPDATE로 전이한다 (paper design D2 준용).
 *
 * <p>clientMessageId는 user·assistant 두 행에 같이 저장한다 — 재전송 멱등 판정(user 행)과
 * DUPLICATE_MESSAGE 응답의 messageId·status 조회(assistant 행)를 한 인덱스로 해결한다.
 * 유니크는 (client_message_id, role)이다.
 */
@Getter
@Entity
@Table(
        name = "chat_message",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_message_client_id_role",
                columnNames = {"client_message_id", "role"}),
        indexes = @Index(
                name = "ix_chat_message_session_created",
                columnList = "session_id, created_at"))
public class ChatMessage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private ChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16, updatable = false)
    private ChatMessageRole role;

    /** assistant는 완료 전까지 null. 완료 시 조건부 UPDATE로 1회 채워진다. */
    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChatMessageStatus status;

    @Column(name = "client_message_id", nullable = false, updatable = false)
    private UUID clientMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ChatMessage() {
        // JPA
    }

    private ChatMessage(ChatSession session, ChatMessageRole role, String content,
            ChatMessageStatus status, UUID clientMessageId, Instant now) {
        this.id = UUID.randomUUID();
        this.session = session;
        this.role = role;
        this.content = content;
        this.status = status;
        this.clientMessageId = clientMessageId;
        this.createdAt = now;
        this.completedAt = status == ChatMessageStatus.COMPLETED ? now : null;
    }

    /** 사용자 질문. 저장 즉시 COMPLETED다. */
    public static ChatMessage userMessage(
            ChatSession session, UUID clientMessageId, String content, Instant now) {
        Objects.requireNonNull(content, "content");
        return new ChatMessage(session, ChatMessageRole.USER, content,
                ChatMessageStatus.COMPLETED, clientMessageId, now);
    }

    /** 생성 중인 assistant 답변 자리. content는 완료 시 조건부 UPDATE로 채운다. */
    public static ChatMessage assistantGenerating(
            ChatSession session, UUID clientMessageId, Instant now) {
        return new ChatMessage(session, ChatMessageRole.ASSISTANT, null,
                ChatMessageStatus.GENERATING, clientMessageId, now);
    }
}
