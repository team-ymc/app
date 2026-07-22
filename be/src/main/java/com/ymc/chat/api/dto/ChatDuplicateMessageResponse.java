package com.ymc.chat.api.dto;

import java.util.UUID;

import com.ymc.chat.domain.ChatMessageStatus;

/** 계약의 `ChatDuplicateMessageError` 스키마 (409, code = DUPLICATE_MESSAGE). */
public record ChatDuplicateMessageResponse(
        String code, String message, UUID sessionId, UUID messageId, ChatMessageStatus status) {

    public static ChatDuplicateMessageResponse of(
            String message, UUID sessionId, UUID messageId, ChatMessageStatus status) {
        return new ChatDuplicateMessageResponse("DUPLICATE_MESSAGE", message, sessionId, messageId, status);
    }
}
