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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * 유니크는 (client_message_id, role)이다. (session_id, seq)도 유니크 — 채번 안전망 겸 정렬 인덱스.
 */
@Getter
@Entity
@Table(
        name = "chat_message",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_chat_message_client_id_role",
                        columnNames = {"client_message_id", "role"}),
                // 앱 채번(max+1)의 DB 안전망 — 잠금 없이 insert하는 미래 코드가 중복 seq를
                // 조용히 저장하지 못하게 한다. 유니크 인덱스가 히스토리 정렬 조회도 겸한다.
                @UniqueConstraint(
                        name = "uk_chat_message_session_seq",
                        columnNames = {"session_id", "seq"})})
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

    /** user 메시지의 선택 영역 앵커. assistant와 선택 없는 질문은 null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection", columnDefinition = "jsonb", updatable = false)
    private ChatSelection selection;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChatMessageStatus status;

    @Column(name = "client_message_id", nullable = false, updatable = false)
    private UUID clientMessageId;

    /** 세션 내 단조 증가 순번 — 히스토리 정렬 키. 세션 행 잠금 하에서 부여된다 (YMC-260 설계 §2). */
    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ChatMessage() {
        // JPA
    }

    private ChatMessage(ChatSession session, ChatMessageRole role, String content,
            ChatSelection selection, ChatMessageStatus status, UUID clientMessageId, int seq,
            Instant now) {
        this.id = UUID.randomUUID();
        this.session = session;
        this.role = role;
        this.content = content;
        this.selection = selection;
        this.status = status;
        this.clientMessageId = clientMessageId;
        this.seq = seq;
        this.createdAt = now;
        this.completedAt = status == ChatMessageStatus.COMPLETED ? now : null;
    }

    /** 사용자 질문. 저장 즉시 COMPLETED다. */
    public static ChatMessage userMessage(ChatSession session, UUID clientMessageId,
            String content, ChatSelection selection, int seq, Instant now) {
        Objects.requireNonNull(content, "content");
        return new ChatMessage(session, ChatMessageRole.USER, content, selection,
                ChatMessageStatus.COMPLETED, clientMessageId, seq, now);
    }

    /** 생성 중인 assistant 답변 자리. content는 완료 시 조건부 UPDATE로 채운다. */
    public static ChatMessage assistantGenerating(
            ChatSession session, UUID clientMessageId, int seq, Instant now) {
        return new ChatMessage(session, ChatMessageRole.ASSISTANT, null, null,
                ChatMessageStatus.GENERATING, clientMessageId, seq, now);
    }
}
