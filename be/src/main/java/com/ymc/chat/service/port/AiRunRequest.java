package com.ymc.chat.service.port;

import java.util.List;

import com.ymc.chat.api.dto.ChatSelectionDto;

/** BE↔AI 계약(universal-pdf-agent-run-stream.yml) 호출 입력. thread_id = sessionId, paper_id = paperId 문자열. selections는 배열 순서 그대로 전달하며 null이면 논문 전체다. */
public record AiRunRequest(String threadId, String paperId, String message, List<ChatSelectionDto> selections) {
}
