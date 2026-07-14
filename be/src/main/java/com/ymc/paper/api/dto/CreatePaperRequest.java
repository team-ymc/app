package com.ymc.paper.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 계약 `CreatePaperRequest`.
 *
 * <p>contentType이 PDF인지는 여기서 {@code @Pattern}으로 보지 않는다 — 위반 시 코드가
 * {@code VALIDATION_ERROR}가 되어버리는데, 계약은 {@code UNSUPPORTED_FILE_TYPE}을 요구한다.
 * 값의 허용 여부 판정은 서비스가 한다.
 */
public record CreatePaperRequest(
        @NotBlank(message = "필수 항목입니다.") String filename,
        @NotBlank(message = "필수 항목입니다.") String contentType) {
}
