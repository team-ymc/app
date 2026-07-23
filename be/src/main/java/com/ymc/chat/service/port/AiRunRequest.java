package com.ymc.chat.service.port;

/** BE↔AI 계약(simple-agent-run-stream.yml)의 request body. thread_id = sessionId 문자열. */
public record AiRunRequest(String threadId, String message) {
}
