package com.ymc.paper.infra.messaging;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.infra.messaging.message.KnowledgeCompileResultMessage;
import com.ymc.paper.service.KnowledgeCompileResultService;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;

/**
 * `knowledge-compile-results` 인바운드 어댑터. ack 규칙은 {@link ParseResultListener}와 같다 —
 * 비복구 입력(malformed JSON, 계약 위반)은 WARN 후 정상 반환(ack), 일시 장애는 예외 전파(재전달).
 */
@Component
@RequiredArgsConstructor
public class KnowledgeCompileResultListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCompileResultListener.class);

    private final ObjectMapper objectMapper;
    private final KnowledgeCompileResultService resultService;

    @SqsListener("${aws.sqs.knowledge-compile-result-queue}")
    public void onCompileResult(String rawPayload) {
        KnowledgeCompileResultMessage message;
        try {
            message = objectMapper.readValue(rawPayload, KnowledgeCompileResultMessage.class);
        } catch (JacksonException e) {
            log.warn("knowledge-compile-result 역직렬화 실패, 폐기: payload={}", rawPayload, e);
            return;
        }

        Optional<String> violation = message.contractViolation();
        if (violation.isPresent()) {
            log.warn("knowledge-compile-result 계약 위반, 폐기: reason={}, payload={}", violation.get(), rawPayload);
            return;
        }

        resultService.apply(message.paperId(), message.terminalStatus(), message.errorCode(),
                message.manifestKey());
    }
}
