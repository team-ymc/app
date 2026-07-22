// chat/service/ChatStartResult.java
package com.ymc.chat.service;

import java.util.UUID;

/** 시작 트랜잭션 commit 후 relay·SSE event 구성에 필요한 식별자 묶음. */
public record ChatStartResult(
        UUID paperId, UUID sessionId, UUID assistantMessageId, UUID clientMessageId) {
}
