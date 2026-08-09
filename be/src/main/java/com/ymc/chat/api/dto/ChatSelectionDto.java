package com.ymc.chat.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 계약의 ChatSelection. 형식 검증만 한다 — 블록 존재·atomic·상한 판정은 AI 소관. */
public record ChatSelectionDto(
        @NotNull @Valid Anchor start,
        @NotNull @Valid Anchor end) {

    /** offset은 UTF-16 code unit. null이면 블록 단위 앵커다. */
    public record Anchor(@NotBlank String blockId, @Min(0) Integer offset) {
    }
}
