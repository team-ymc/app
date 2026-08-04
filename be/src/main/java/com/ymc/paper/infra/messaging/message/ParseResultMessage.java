package com.ymc.paper.infra.messaging.message;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ymc.paper.domain.PaperStatus;

/**
 * project-docs/contracts/backend-ai/sqs/messaging.yml 0.2.0 `ParseResult` 대응.
 * wire는 snake_case·status 소문자다. completed는 {@code manifest_key}·{@code message}가 필수,
 * failed는 {@code error.code}가 필수다.
 *
 * <p>계약은 {@code additionalProperties: false}지만 수신 측은 관대하게 둔다 — 모르는 필드가 하나
 * 늘었다고 결과를 잃는 편이 더 나쁘다. spec이 비복구로 규정한 위반만 {@link #contractViolation()}이 잡는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParseResultMessage(
        @JsonProperty("paper_id") UUID paperId,
        String status,
        String message,
        @JsonProperty("manifest_key") String manifestKey,
        ErrorDetail error) {

    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorDetail(String code, String message) {
    }

    /**
     * 재시도해도 달라지지 않는 계약 위반을 찾는다.
     *
     * <p>계약이 required로 둔 {@code message}·{@code error.message}는 여기서 검사하지 않는다 —
     * BE가 소비하지 않는 설명용 필드라, 그 누락 때문에 결과를 폐기하면 상태 전이를 잃고 논문이
     * PROCESSING에 갇힌다. 처리에 실제로 필요한 필드의 누락만 위반으로 본다.
     *
     * @return 위반 사유. 유효하면 {@link Optional#empty()}
     */
    public Optional<String> contractViolation() {
        if (paperId == null) {
            return Optional.of("paper_id가 없습니다.");
        }
        if (status == null) {
            return Optional.of("status가 없습니다.");
        }
        if (terminalStatus() == null) {
            return Optional.of("파싱 서버가 낼 수 없는 status입니다: " + status);
        }
        if (terminalStatus() == PaperStatus.COMPLETED
                && (manifestKey == null || manifestKey.isBlank())) {
            return Optional.of("status=completed인데 manifest_key가 없습니다.");
        }
        if (terminalStatus() == PaperStatus.FAILED && errorCode() == null) {
            return Optional.of("status=failed인데 error.code가 없습니다.");
        }
        return Optional.empty();
    }

    /** 계약의 소문자 status를 BE {@link PaperStatus}로 매핑한다. 계약에 없는 값이면 null. */
    public PaperStatus terminalStatus() {
        if (STATUS_COMPLETED.equals(status)) {
            return PaperStatus.COMPLETED;
        }
        if (STATUS_FAILED.equals(status)) {
            return PaperStatus.FAILED;
        }
        return null;
    }

    /** 실패 코드 (계약 enum 4종). 해석하지 않고 저장만 한다. */
    public String errorCode() {
        if (error == null || error.code() == null || error.code().isBlank()) {
            return null;
        }
        return error.code();
    }
}
