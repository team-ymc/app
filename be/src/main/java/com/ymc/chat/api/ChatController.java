// chat/api/ChatController.java
package com.ymc.chat.api;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ymc.chat.api.dto.ChatMessageStreamRequest;
import com.ymc.chat.infra.ai.ChatStreamProperties;
import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.chat.service.ChatStreamService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 계약(openapi.yaml)의 /api/papers/{paperId}/chat/messages. HTTP ↔ DTO 변환만 한다. */
@RestController
@RequestMapping("/api/papers/{paperId}/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatCommandService chatCommandService;
    private final ChatStreamService chatStreamService;
    private final ChatStreamProperties chatStreamProperties;

    /** 질문을 저장(commit)한 뒤 SSE 스트림을 시작한다. 스트림 전 오류는 JSON으로 반환된다. */
    @PostMapping(path = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> createMessageStream(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paperId,
            @Valid @RequestBody ChatMessageStreamRequest request) {

        UUID ownerId = UUID.fromString(jwt.getSubject());
        ChatStartResult started = chatCommandService.start(
                ownerId, paperId, request.sessionId(), request.clientMessageId(), request.content());

        SseEmitter emitter = new SseEmitter(chatStreamProperties.emitterTimeout().toMillis());
        chatStreamService.begin(emitter, started, request.content());

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform") // 중간 계층 버퍼링·캐싱 방지 (계약)
                .body(emitter);
    }
}
