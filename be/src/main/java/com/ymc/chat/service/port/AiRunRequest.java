package com.ymc.chat.service.port;

import com.ymc.chat.api.dto.ChatSelectionDto;

/** BE↔AI 계약(inline-pdf-agent-run-stream.yml)의 request body. thread_id = sessionId, paper_id = paperId 문자열. */
public record AiRunRequest(String threadId, String paperId, String message, ChatSelectionDto selection) {
}
