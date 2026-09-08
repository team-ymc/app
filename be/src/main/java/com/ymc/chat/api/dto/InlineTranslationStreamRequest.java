package com.ymc.chat.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 계약의 `InlineTranslationStreamRequest`. selection은 단수·필수다. */
public record InlineTranslationStreamRequest(@NotNull @Valid ChatSelectionDto selection) {
}
