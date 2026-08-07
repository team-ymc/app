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
 * BE↔AI 계약(inline-pdf-agent-run-stream.yml)의 WebClient 구현.
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

    static final String STREAM_PATH = "/api/v1/agents/inline-pdf-agent/runs/stream";

    private final WebClient aiWebClient;
    private final Scheduler chatRelayScheduler;
    private final ChatStreamProperties chatStreamProperties;
    private final ObjectMapper objectMapper;

    /** wire 형식은 snake_case (계약) — 코드 컨벤션과 경계에서 변환한다. */
    record StreamRequestBody(
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("paper_id") String paperId,
            String message,
            SelectionBody selection) {

        record SelectionBody(AnchorBody start, AnchorBody end) {
        }

        record AnchorBody(@JsonProperty("block_id") String blockId, Integer offset) {
        }

        static SelectionBody selectionOf(com.ymc.chat.domain.ChatSelection selection) {
            if (selection == null) {
                return null;
            }
            return new SelectionBody(
                    new AnchorBody(selection.start().blockId(), selection.start().offset()),
                    new AnchorBody(selection.end().blockId(), selection.end().offset()));
        }
    }

    @Override
    public AiRunHandle stream(AiRunRequest request, AiStreamListener listener) {
        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> events = aiWebClient.post()
                .uri(STREAM_PATH)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new StreamRequestBody(request.threadId(), request.paperId(),
                        request.message(), StreamRequestBody.selectionOf(request.selection())))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
        Disposable subscription = events
                .timeout(chatStreamProperties.idleTimeout())
                .publishOn(chatRelayScheduler)
                .subscribe(
                        (ServerSentEvent<String> event) -> dispatch(event, request, listener, terminalSeen),
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

    private void dispatch(ServerSentEvent<String> event, AiRunRequest request,
            AiStreamListener listener, AtomicBoolean terminalSeen) {
        String name = event.event() == null ? "" : event.event();
        try {
            switch (name) {
                case "run.started", "message.delta", "message.completed", "run.completed", "run.failed" -> {
                    JsonNode data = objectMapper.readTree(event.data());
                    verifyIdentifiers(data, request);
                    dispatchKnown(name, data, listener, terminalSeen);
                }
                default -> log.debug("알 수 없는 AI event 무시: {}", name);
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // reactive 체인으로 던진다 — Reactor가 구독을 취소(=연결 종료=AI 생성 취소)하고
            // error 경로를 타서 onTransportError가 정확히 한 번 호출된다
            throw new IllegalStateException("AI event 처리 실패: " + name, e);
        }
    }

    private void dispatchKnown(String name, JsonNode data,
            AiStreamListener listener, AtomicBoolean terminalSeen) {
        switch (name) {
            case "run.started" -> listener.onRunStarted();
            case "message.delta" -> listener.onDelta(requiredText(data, "delta"));
            case "message.completed" -> listener.onMessageCompleted(requiredText(data, "message"));
            case "run.completed" -> {
                terminalSeen.set(true);
                listener.onRunCompleted();
            }
            case "run.failed" -> {
                terminalSeen.set(true);
                JsonNode error = data.get("error");
                if (error == null || !error.isObject()) {
                    throw new IllegalArgumentException("AI run.failed에 error 객체가 없습니다.");
                }
                listener.onRunFailed(requiredText(error, "code"), requiredText(error, "message"));
            }
            default -> throw new IllegalArgumentException("dispatchKnown에 올 수 없는 event: " + name);
        }
    }

    /** 잘못 라우팅된 스트림의 응답이 이 run에 귀속되지 않게 모든 이벤트에서 확인한다. */
    private static void verifyIdentifiers(JsonNode data, AiRunRequest request) {
        if (!request.threadId().equals(requiredText(data, "thread_id"))
                || !request.paperId().equals(requiredText(data, "paper_id"))) {
            throw new IllegalArgumentException("AI event의 thread_id·paper_id가 요청과 다릅니다.");
        }
    }

    private static String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "AI event data에 '" + fieldName + "' 문자열 필드가 없습니다.");
        }
        return value.asText();
    }
}
