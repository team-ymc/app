// chat/api/dto/ChatMessageStreamRequest.java
package com.ymc.chat.api.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 계약의 `ChatMessageStreamRequest`. sessionId는 첫 질문에서, selection은 전체 질문에서 null이다. */
public record ChatMessageStreamRequest(
        UUID sessionId,
        @NotNull UUID clientMessageId,
        @NotBlank String content,
        @Valid ChatSelectionDto selection) {
}
