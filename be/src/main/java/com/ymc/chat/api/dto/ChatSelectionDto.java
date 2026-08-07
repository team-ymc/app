// chat/api/dto/ChatSelectionDto.java
package com.ymc.chat.api.dto;

import com.ymc.chat.domain.ChatSelection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 계약의 `ChatSelection`. shape 검증만 한다 — 블록 존재·순서 검증은 AI 담당. */
public record ChatSelectionDto(
        @NotNull @Valid AnchorDto start,
        @NotNull @Valid AnchorDto end) {

    public record AnchorDto(@NotBlank String blockId, @PositiveOrZero Integer offset) {
    }

    public ChatSelection toDomain() {
        return new ChatSelection(
                new ChatSelection.Anchor(start.blockId(), start.offset()),
                new ChatSelection.Anchor(end.blockId(), end.offset()));
    }

    public static ChatSelectionDto from(ChatSelection selection) {
        if (selection == null) {
            return null;
        }
        return new ChatSelectionDto(
                new AnchorDto(selection.start().blockId(), selection.start().offset()),
                new AnchorDto(selection.end().blockId(), selection.end().offset()));
    }
}
