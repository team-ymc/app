package com.ymc.chat.service.port;

import com.ymc.chat.api.dto.ChatSelectionDto;

/** BE↔AI 계약(universal-pdf-agent-run-stream.yml) 호출 입력. thread_id = sessionId, paper_id = paperId 문자열. 단일 selection은 어댑터가 selections 배열로 변환한다. */
public record AiRunRequest(String threadId, String paperId, String message, ChatSelectionDto selection) {
}
