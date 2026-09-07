package com.ymc.chat.infra.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ymc.chat.api.dto.ChatSelectionDto;
import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

import lombok.RequiredArgsConstructor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/**
 * BE↔AI 계약(universal-pdf-agent-run-stream.yml)의 WebClient 구현 (설계 §4).
 *
 * <p>reactive 체인은 이 클래스 밖으로 나가지 않는다 — 리스너 콜백은 전부
 * {@code chatRelayScheduler}(virtual thread)에서 순서대로 호출되므로 relay는 블로킹해도 된다.
 * 침묵 감지는 {@code Flux.timeout}(구독 시점부터 첫 이벤트에도 적용), 총 시한은 relay 워치독 담당.
 */
@Component
@ConditionalOnProperty(name = "ai.fake-stream", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class AiAgentWebClientAdapter implements AiAgentStreamPort {

    static final String STREAM_PATH = "/api/v1/agents/universal-pdf-agent/runs/stream";

    private final WebClient aiWebClient;
    private final Scheduler chatRelayScheduler;
    private final ChatStreamProperties chatStreamProperties;
    private final AiSseEvents aiSseEvents;

    /** wire 형식은 snake_case (계약) — 코드 컨벤션과 경계에서 변환한다. */
    record StreamRequestBody(
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("paper_id") String paperId,
            String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<SelectionBody> selections) {
    }

    record SelectionBody(AnchorBody start, AnchorBody end) {
        /** 배열 순서 그대로 매핑한다 — null이면 필드 생략 (빈 배열은 계약 위반이라 DTO 검증이 막는다). */
        static List<SelectionBody> listFrom(List<ChatSelectionDto> dtos) {
            return dtos == null ? null
                    : dtos.stream()
                            .map(dto -> new SelectionBody(AnchorBody.from(dto.start()), AnchorBody.from(dto.end())))
                            .toList();
        }
    }

    record AnchorBody(
            @JsonProperty("block_id") String blockId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer offset) {
        static AnchorBody from(ChatSelectionDto.Anchor anchor) {
            return new AnchorBody(anchor.blockId(), anchor.offset());
        }
    }

    @Override
    public AiRunHandle stream(AiRunRequest request, AiStreamListener listener) {
        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> events = aiWebClient.post()
                .uri(STREAM_PATH)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new StreamRequestBody(request.threadId(), request.paperId(), request.message(),
                        SelectionBody.listFrom(request.selections())))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
        Disposable subscription = events
                .timeout(chatStreamProperties.idleTimeout())
                .publishOn(chatRelayScheduler)
                .subscribe(
                        (ServerSentEvent<String> event) -> aiSseEvents.dispatch(event, listener, terminalSeen),
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
}
