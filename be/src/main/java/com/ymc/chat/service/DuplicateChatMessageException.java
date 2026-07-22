package com.ymc.chat.service;

import java.util.UUID;

import com.ymc.chat.domain.ChatMessageStatus;

import lombok.Getter;

/**
 * 같은 clientMessageId·같은 content의 재전송. 새 run을 만들지 않았고, 계약의
 * ChatDuplicateMessageError(409)로 기존 실행의 식별자·상태를 돌려준다.
 */
@Getter
public class DuplicateChatMessageException extends RuntimeException {

    private final UUID sessionId;
    private final UUID messageId;
    private final ChatMessageStatus status;

    public DuplicateChatMessageException(UUID sessionId, UUID messageId, ChatMessageStatus status) {
        super("이미 처리된(또는 처리 중인) 요청입니다.");
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.status = status;
    }
}
