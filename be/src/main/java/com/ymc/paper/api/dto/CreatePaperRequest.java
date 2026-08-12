package com.ymc.paper.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 계약 `CreatePaperRequest`.
 *
 * <p>contentType이 PDF인지는 여기서 {@code @Pattern}으로 보지 않는다 — 위반 시 코드가
 * {@code VALIDATION_ERROR}가 되어버리는데, 계약은 {@code UNSUPPORTED_FILE_TYPE}을 요구한다.
 * 값의 허용 여부 판정은 서비스가 한다. size 상한도 같은 이유로 서비스가 본다.
 * checksumSha256은 전용 에러 코드가 없어 형식 위반을 여기서 {@code @Pattern}으로 잡는다.
 *
 * <p>size는 presigned PUT의 서명에 들어갈 정확한 바이트 수다. 업로드가 이 값과 다르면 S3가 거절한다.
 */
public record CreatePaperRequest(
        @NotBlank(message = "필수 항목입니다.") String filename,
        @NotBlank(message = "필수 항목입니다.") String contentType,
        @NotNull(message = "필수 항목입니다.") @Positive(message = "0보다 커야 합니다.") Long size,
        @NotNull(message = "필수 항목입니다.")
        @Pattern(regexp = "^[A-Za-z0-9+/]{43}=$", message = "표준 Base64 SHA-256이어야 합니다.")
        String checksumSha256) {
}
