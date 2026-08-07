// chat/api/dto/ChatMessageItemResponse.java
package com.ymc.chat.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;

/** 계약 ChatMessageItem. content는 GENERATING·FAILED assistant에서 null이다. */
public record ChatMessageItemResponse(
        UUID messageId, ChatMessageRole role, String content,
        ChatMessageStatus status, int seq, Instant createdAt, ChatSelectionDto selection) {

    public static ChatMessageItemResponse from(ChatMessage message) {
        return new ChatMessageItemResponse(
                message.getId(), message.getRole(), message.getContent(),
                message.getStatus(), message.getSeq(), message.getCreatedAt(),
                ChatSelectionDto.from(message.getSelection()));
    }
}
