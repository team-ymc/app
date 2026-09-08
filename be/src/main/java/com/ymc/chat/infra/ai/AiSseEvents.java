package com.ymc.chat.infra.ai;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.service.port.AiStreamListener;

/**
 * BE↔AI SSE 프레임(run.started/message.delta/message.completed/run.completed/run.failed)을
 * {@link AiStreamListener} 콜백으로 옮긴다. 채팅·번역 어댑터가 공유한다.
 */
@Component
public class AiSseEvents {

    private static final Logger log = LoggerFactory.getLogger(AiSseEvents.class);

    private final ObjectMapper objectMapper;

    public AiSseEvents(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 파싱 실패는 IllegalStateException으로 던진다 — Reactor가 구독을 취소(=연결 종료=AI 생성 취소)하고
     * error 경로를 타서 onTransportError가 정확히 한 번 호출된다.
     */
    public void dispatch(ServerSentEvent<String> event, AiStreamListener listener, AtomicBoolean terminalSeen) {
        String name = event.event() == null ? "" : event.event();
        try {
            switch (name) {
                case "run.started" -> listener.onRunStarted();
                case "message.delta" -> listener.onDelta(textField(event.data(), "delta"));
                case "message.completed" -> listener.onMessageCompleted(textField(event.data(), "message"));
                case "run.completed" -> {
                    terminalSeen.set(true);
                    listener.onRunCompleted(optionalCost(event.data()));
                }
                case "run.failed" -> {
                    terminalSeen.set(true);
                    JsonNode error = errorObject(event.data());
                    listener.onRunFailed(requireText(error, "code"), requireText(error, "message"));
                }
                default -> log.debug("알 수 없는 AI event 무시: {}", name);
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("AI event data 파싱 실패: " + name, e);
        }
    }

    /** run.completed의 선택 필드. 없거나 숫자가 아니면 null — 성공 처리를 막지 않는다. data 자체가 깨진 건 계약 위반이라 위로 던진다. */
    private BigDecimal optionalCost(String data) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(data).get("estimated_cost_usd");
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }

    private String textField(String data, String fieldName) throws JsonProcessingException {
        return requireText(objectMapper.readTree(data), fieldName);
    }

    private JsonNode errorObject(String data) throws JsonProcessingException {
        JsonNode error = objectMapper.readTree(data).get("error");
        if (error == null || !error.isObject()) {
            throw new IllegalArgumentException("run.failed data에 'error' 객체가 없습니다.");
        }
        return error;
    }

    private static String requireText(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("AI event data에 '" + fieldName + "' 문자열 필드가 없습니다.");
        }
        return value.asText();
    }
}
