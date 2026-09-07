package com.ymc.chat.infra.ai;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.chat.service.port.AiTranslateRequest;
import com.ymc.chat.service.port.AiTranslateStreamPort;

import lombok.RequiredArgsConstructor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/**
 * BE↔AI 계약(inline-translate-agent-run-stream.yml)의 WebClient 구현. 구독·타임아웃·스레딩은
 * {@link AiAgentWebClientAdapter}와 같고 경로·요청 바디만 다르다.
 */
@Component
@ConditionalOnProperty(name = "ai.fake-stream", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class AiTranslateWebClientAdapter implements AiTranslateStreamPort {

    static final String STREAM_PATH = "/api/v1/agents/inline-translate-agent/runs/stream";

    private final WebClient aiWebClient;
    private final Scheduler chatRelayScheduler;
    private final ChatStreamProperties chatStreamProperties;
    private final AiSseEvents aiSseEvents;

    /** wire 형식은 snake_case (계약). selection은 단수·필수다. */
    record StreamRequestBody(
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("paper_id") String paperId,
            AiAgentWebClientAdapter.SelectionBody selection) {
    }

    @Override
    public AiRunHandle stream(AiTranslateRequest request, AiStreamListener listener) {
        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> events = aiWebClient.post()
                .uri(STREAM_PATH)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new StreamRequestBody(request.threadId(), request.paperId(),
                        AiAgentWebClientAdapter.SelectionBody.from(request.selection())))
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
        return subscription::dispose;
    }
}
