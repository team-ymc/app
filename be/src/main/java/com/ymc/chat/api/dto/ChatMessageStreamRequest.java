// chat/api/dto/ChatMessageStreamRequest.java
package com.ymc.chat.api.dto;

import java.util.List;
import java.util.UUID;

import org.hibernate.validator.constraints.UniqueElements;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 계약의 `ChatMessageStreamRequest`. sessionId는 첫 질문에서 null이다. selections는 null이면 논문 전체다. */
public record ChatMessageStreamRequest(
        UUID sessionId,
        @NotNull UUID clientMessageId,
        @NotBlank String content,
        @Size(min = 1, max = 5) @UniqueElements List<@NotNull @Valid ChatSelectionDto> selections) {
}
