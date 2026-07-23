package com.ymc.chat.infra.ai;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

import lombok.RequiredArgsConstructor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/**
 * BE↔AI 계약(simple-agent-run-stream.yml)의 WebClient 구현 (설계 §4).
 *
 * <p>reactive 체인은 이 클래스 밖으로 나가지 않는다 — 리스너 콜백은 전부
 * {@code chatRelayScheduler}(virtual thread)에서 순서대로 호출되므로 relay는 블로킹해도 된다.
 * 침묵 감지는 {@code Flux.timeout}(구독 시점부터 첫 이벤트에도 적용), 총 시한은 relay 워치독 담당.
 */
@Component
@ConditionalOnProperty(name = "ai.fake-stream", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class AiAgentWebClientAdapter implements AiAgentStreamPort {

    private static final Logger log = LoggerFactory.getLogger(AiAgentWebClientAdapter.class);

    static final String STREAM_PATH = "/api/v1/agents/simple-agent/runs/stream";

    private final WebClient aiWebClient;
    private final Scheduler chatRelayScheduler;
    private final ChatStreamProperties chatStreamProperties;
    private final ObjectMapper objectMapper;

    /** wire 형식은 snake_case (계약) — 코드 컨벤션과 경계에서 변환한다. */
    record StreamRequestBody(@JsonProperty("thread_id") String threadId, String message) {
    }

    @Override
    public AiRunHandle stream(AiRunRequest request, AiStreamListener listener) {
        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> events = aiWebClient.post()
                .uri(STREAM_PATH)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new StreamRequestBody(request.threadId(), request.message()))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
        Disposable subscription = events
                .timeout(chatStreamProperties.idleTimeout())
                .publishOn(chatRelayScheduler)
                .subscribe(
                        (ServerSentEvent<String> event) -> dispatch(event, listener, terminalSeen),
                        (Throwable cause) -> listener.onTransportError(
                                cause instanceof Exception ex ? ex : new RuntimeException(cause)),
                        () -> {
                            if (!terminalSeen.get()) {
                                listener.onTransportError(new IllegalStateException(
                                        "terminal event 없이 upstream 스트림이 종료되었습니다."));
                            }
                        });
        return subscription::dispose; // dispose → 연결 종료 → AI가 생성 취소 (ADR-004)
    }

    private void dispatch(
            ServerSentEvent<String> event, AiStreamListener listener, AtomicBoolean terminalSeen) {
        String name = event.event() == null ? "" : event.event();
        try {
            switch (name) {
                case "run.started" -> listener.onRunStarted();
                case "message.delta" -> listener.onDelta(textField(event.data(), "delta"));
                case "message.completed" -> listener.onMessageCompleted(textField(event.data(), "message"));
                case "run.completed" -> {
                    terminalSeen.set(true);
                    listener.onRunCompleted();
                }
                case "run.failed" -> {
                    terminalSeen.set(true);
                    listener.onRunFailed(textField(event.data(), "error"));
                }
                default -> log.debug("알 수 없는 AI event 무시: {}", name);
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // reactive 체인으로 던진다 — Reactor가 구독을 취소(=연결 종료=AI 생성 취소)하고
            // error 경로를 타서 onTransportError가 정확히 한 번 호출된다
            throw new IllegalStateException("AI event data 파싱 실패: " + name, e);
        }
    }

    /** data JSON에서 필수 문자열 필드를 꺼낸다. 없으면 계약 위반 — transport error로 처리된다. */
    private String textField(String data, String fieldName) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(data);
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "AI event data에 '" + fieldName + "' 문자열 필드가 없습니다.");
        }
        return value.asText();
    }
}
