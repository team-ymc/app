package com.ymc.paper.infra.messaging;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.infra.messaging.message.ParseResultMessage;
import com.ymc.paper.service.ParseResultService;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;

/**
 * `parse-results` 인바운드 어댑터 (design D1·D7).
 *
 * <p>payload를 {@link ParseResultMessage}가 아니라 {@code String}으로 받는 이유:
 * 프레임워크가 대신 역직렬화하면 malformed JSON이 리스너 밖에서 터져 ack 정책을 우리가 못 정한다.
 * 원문을 받아 직접 파싱해야 "역직렬화 실패도 비복구 입력으로 보고 정상 소비"를 지킬 수 있다.
 *
 * <p>ack 규칙:
 * <ul>
 *   <li><b>비복구 입력</b>(malformed JSON, 계약 위반) → 경고 로그 후 정상 반환 = ack.
 *       재시도해도 같은 결과라 큐에 되돌리면 poison message가 된다.</li>
 *   <li><b>일시적 장애</b>(DB 연결 실패·타임아웃) → 예외를 전파 = ack 안 함. SQS가 재전달한다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ParseResultListener {

    private static final Logger log = LoggerFactory.getLogger(ParseResultListener.class);

    private final ObjectMapper objectMapper;
    private final ParseResultService parseResultService;

    @SqsListener("${aws.sqs.parse-result-queue}")
    public void onParseResult(String rawPayload) {
        ParseResultMessage message;
        try {
            message = objectMapper.readValue(rawPayload, ParseResultMessage.class);
        } catch (JacksonException e) {
            // 재시도해도 같은 결과다. ack해서 poison message가 되지 않게 한다.
            log.warn("parse-result 역직렬화 실패, 폐기: payload={}", rawPayload, e);
            return;
        }

        Optional<String> violation = message.contractViolation();
        if (violation.isPresent()) {
            log.warn("parse-result 계약 위반, 폐기: reason={}, payload={}", violation.get(), rawPayload);
            return;
        }

        // 여기서부터의 예외(DB 장애 등)는 삼키지 않는다 — 전파해야 재전달된다.
        parseResultService.apply(message.paperId(), message.terminalStatus(), message.errorCode());
    }
}
