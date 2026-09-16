package com.ymc.paper.infra.messaging.message;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ymc.paper.domain.CompileStatus;

/**
 * messaging.yml 0.3.0 `KnowledgeCompileResult`. wire는 snake_case·status 소문자다.
 * completed는 manifest_key, failed는 error.code가 필수다. 수신은 관대하게 — 모르는 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeCompileResultMessage(
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

    /** 재시도해도 달라지지 않는 계약 위반. 처리에 실제로 필요한 필드의 누락만 본다. */
    public Optional<String> contractViolation() {
        if (paperId == null) {
            return Optional.of("paper_id가 없습니다.");
        }
        if (status == null) {
            return Optional.of("status가 없습니다.");
        }
        if (terminalStatus() == null) {
            return Optional.of("컴파일 워커가 낼 수 없는 status입니다: " + status);
        }
        if (terminalStatus() == CompileStatus.COMPLETED && (manifestKey == null || manifestKey.isBlank())) {
            return Optional.of("status=completed인데 manifest_key가 없습니다.");
        }
        if (terminalStatus() == CompileStatus.FAILED && errorCode() == null) {
            return Optional.of("status=failed인데 error.code가 없습니다.");
        }
        return Optional.empty();
    }

    /** 계약의 소문자 status → CompileStatus. 계약에 없는 값이면 null. */
    public CompileStatus terminalStatus() {
        if (STATUS_COMPLETED.equals(status)) {
            return CompileStatus.COMPLETED;
        }
        if (STATUS_FAILED.equals(status)) {
            return CompileStatus.FAILED;
        }
        return null;
    }

    /** 실패 코드. 계약 enum 5종이지만 해석하지 않고 저장만 한다 — 새 코드가 늘어도 결과를 잃지 않는다. */
    public String errorCode() {
        if (error == null || error.code() == null || error.code().isBlank()) {
            return null;
        }
        return error.code();
    }
}
