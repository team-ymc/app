// chat/api/dto/ChatSessionSummaryResponse.java
package com.ymc.chat.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.ymc.chat.domain.ChatSession;

/** 계약 ChatSessionSummary. */
public record ChatSessionSummaryResponse(
        UUID sessionId, String title, Instant lastMessageAt, Instant createdAt) {

    public static ChatSessionSummaryResponse from(ChatSession session) {
        return new ChatSessionSummaryResponse(
                session.getId(), session.getTitle(),
                session.getLastMessageAt(), session.getCreatedAt());
    }
}
