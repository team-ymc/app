package com.ymc.paper.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 계약 `RenamePaperRequest`. trim은 서비스가 한다. */
public record RenamePaperRequest(
        @NotBlank(message = "필수 항목입니다.")
        @Size(max = 255, message = "255자 이하여야 합니다.")
        String filename) {
}
