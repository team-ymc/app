package com.ymc.paper.infra.messaging.message;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.domain.PaperStatus;

/**
 * contracts/schema/parse-result.schema.json 대응. BE가 읽는 것은 envelope뿐이다 (design D7).
 *
 * <p>{@code result}는 역직렬화만 하고 해석·저장하지 않는다 — 본문 구조는 AI 소유이며 미확정이다.
 * 필드를 들여다보지 않겠다는 뜻으로 {@link JsonNode}로 받는다.
 *
 * <p>계약은 {@code additionalProperties: false}지만 수신 측은 관대하게 둔다 — 모르는 필드가 하나
 * 늘었다고 결과를 잃는 편이 더 나쁘다. spec이 비복구로 규정한 위반만 {@link #contractViolation()}이 잡는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParseResultMessage(UUID paperId, String status, ErrorDetail error, JsonNode result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorDetail(String code, String message) {
    }

    /**
     * 재시도해도 달라지지 않는 계약 위반을 찾는다.
     *
     * @return 위반 사유. 유효하면 {@link Optional#empty()}
     */
    public Optional<String> contractViolation() {
        if (paperId == null) {
            return Optional.of("paperId가 없습니다.");
        }
        if (status == null) {
            return Optional.of("status가 없습니다.");
        }
        if (terminalStatus() == null) {
            return Optional.of("파싱 서버가 낼 수 없는 status입니다: " + status);
        }
        if (terminalStatus() == PaperStatus.FAILED && errorCode() == null) {
            return Optional.of("status=FAILED인데 error.code가 없습니다.");
        }
        return Optional.empty();
    }

    /**
     * 계약이 허용하는 결과 상태({@code COMPLETED}·{@code FAILED})로만 매핑한다.
     * 나머지 {@link PaperStatus}는 BE가 소유하므로 파싱 서버가 지정할 수 없다.
     *
     * @return 매핑된 상태. 계약에 없는 값이면 null
     */
    public PaperStatus terminalStatus() {
        if (PaperStatus.COMPLETED.name().equals(status)) {
            return PaperStatus.COMPLETED;
        }
        if (PaperStatus.FAILED.name().equals(status)) {
            return PaperStatus.FAILED;
        }
        return null;
    }

    /** 실패 코드. 해석하지 않고 저장만 한다 (코드 목록은 AI 소유·미확정). */
    public String errorCode() {
        if (error == null || error.code() == null || error.code().isBlank()) {
            return null;
        }
        return error.code();
    }
}
