package com.ymc.paper.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 계약 `RenamePaperRequest`. 길이(1~255)는 trim 뒤에 봐야 하므로 서비스가 검증한다. */
public record RenamePaperRequest(
        @NotBlank(message = "필수 항목입니다.")
        String filename) {
}
