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

import com.ymc.chat.api.dto.InlineTranslationStreamRequest;
import com.ymc.chat.infra.ai.ChatStreamProperties;
import com.ymc.chat.service.TranslationCommandService;
import com.ymc.chat.service.TranslationStartResult;
import com.ymc.chat.service.TranslationStreamService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 계약(openapi.yaml)의 POST /api/papers/{paperId}/inline-translations. HTTP ↔ DTO 변환만 한다. */
@RestController
@RequestMapping("/api/papers/{paperId}/inline-translations")
@RequiredArgsConstructor
public class InlineTranslationController {

    private final TranslationCommandService translationCommandService;
    private final TranslationStreamService translationStreamService;
    private final ChatStreamProperties chatStreamProperties;

    /** run과 예약을 commit한 뒤 SSE 스트림을 시작한다. 스트림 전 오류는 JSON으로 반환된다. */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> createStream(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paperId,
            @Valid @RequestBody InlineTranslationStreamRequest request) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        TranslationStartResult started = translationCommandService.start(ownerId, paperId, request.selection());

        SseEmitter emitter = new SseEmitter(chatStreamProperties.emitterTimeout().toMillis());
        translationStreamService.begin(emitter, started, request.selection());

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-transform")
                .body(emitter);
    }
}
