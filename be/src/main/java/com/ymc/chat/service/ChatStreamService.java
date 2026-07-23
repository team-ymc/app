package com.ymc.chat.service;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ymc.chat.api.dto.ChatSseEventData;
import com.ymc.chat.infra.ai.ChatStreamProperties;
import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

import lombok.RequiredArgsConstructor;

/**
 * 시작 트랜잭션 commit 후의 스트리밍 조율 — message.started 전송, AI 스트림 구독,
 * delta 중계, 종결 시 상태 확정과 terminal event 전송 (ADR-004).
 *
 * <p>YMC-256 범위: fake 포트 기준의 성공·실패 경로. timeout·heartbeat·누적 상한은
 * YMC-257에서 이 클래스에 추가된다 (설계 §4).
 *
 * <p>FE 연결이 끊겨도 upstream 소비와 최종 저장은 계속한다 (계약 frontendDisconnect) —
 * emitter 전송만 스킵한다.
 */
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final AiAgentStreamPort aiAgentStreamPort;
    private final ChatMessageTransitions transitions;
    private final ChatStreamProperties chatStreamProperties;

    /** message.started를 보내고 AI 스트림을 시작한다. 호출 시점은 시작 트랜잭션 commit 후다. */
    public void begin(SseEmitter emitter, ChatStartResult started, String userContent) {
        Run run = new Run(emitter, started);
        run.sendStarted();
        AiRunHandle handle = aiAgentStreamPort.stream(
                new AiRunRequest(started.sessionId().toString(), userContent), run);
        run.attach(handle);
    }

    /** 한 스트림의 상태. 어댑터가 콜백을 직렬 호출하므로 필드 동기화는 FE 단절 플래그만 필요하다. */
    private class Run implements AiStreamListener {

        private final SseEmitter emitter;
        private final ChatStartResult ids;
        private final AtomicBoolean feConnected = new AtomicBoolean(true);
        private final StringBuilder accumulated = new StringBuilder();
        private String finalContent;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final Object sendLock = new Object();
        private volatile AiRunHandle handle;

        private Run(SseEmitter emitter, ChatStartResult ids) {
            this.emitter = emitter;
            this.ids = ids;
            emitter.onCompletion(() -> feConnected.set(false));
            emitter.onError(t -> feConnected.set(false));
            emitter.onTimeout(() -> feConnected.set(false));
        }

        void attach(AiRunHandle handle) {
            this.handle = handle;
        }

        private void cancelUpstream() {
            AiRunHandle current = handle;
            if (current != null) {
                current.cancel();
            }
        }

        private void sendStarted() {
            send("message.started", ChatSseEventData.Started.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), ids.clientMessageId()));
        }

        @Override
        public void onRunStarted() {
            // BE 내부 확인용 — FE에는 보내지 않는다 (계약 x-upstream-event-mapping)
        }

        @Override
        public void onDelta(String delta) {
            if (finished.get()) {
                return; // 이미 종결 — 늦게 도착한 delta는 버린다
            }
            if (accumulated.length() + delta.length() > chatStreamProperties.maxContentLength()) {
                // idle timeout은 "안 올 때"만 잡는다 — "너무 많이 올 때"는 이 상한이 잡는다 (설계 §4)
                log.warn("delta 누적 상한 초과. messageId={} 누적={}자",
                        ids.assistantMessageId(), accumulated.length());
                cancelUpstream();
                failWith("AI_RESPONSE_TOO_LARGE", "답변이 허용 길이를 초과했습니다.", false);
                return;
            }
            accumulated.append(delta);
            send("message.delta", ChatSseEventData.Delta.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), delta));
        }

        @Override
        public void onMessageCompleted(String message) {
            this.finalContent = message; // 아직 성공 아님 — run.completed 대기
        }

        @Override
        public void onRunCompleted() {
            if (!finished.compareAndSet(false, true)) {
                return; // 워치독·상한·transport 중 하나가 이미 종결함
            }
            if (finalContent == null) {
                // message.completed 없이 run.completed — 계약의 AI_PROTOCOL_ERROR
                failLocked("AI_PROTOCOL_ERROR", "답변 생성 결과가 올바르지 않습니다.", false);
                return;
            }
            if (!accumulated.toString().equals(finalContent)) {
                log.warn("누적 delta와 최종 답변이 다릅니다. messageId={} 누적={}자 최종={}자",
                        ids.assistantMessageId(), accumulated.length(), finalContent.length());
            }
            boolean committed;
            try {
                committed = transitions.complete(ids.assistantMessageId(), finalContent);
            } catch (RuntimeException e) {
                log.error("최종 답변 저장 실패. messageId={}", ids.assistantMessageId(), e);
                failLocked("MESSAGE_PERSISTENCE_FAILED", "답변을 저장하지 못했습니다.", true);
                return;
            }
            if (!committed) {
                // 이미 다른 경로가 FAILED로 확정 — 성공 event를 보내지 않는다
                complete();
                return;
            }
            send("message.completed", ChatSseEventData.Completed.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), finalContent));
            complete();
        }

        @Override
        public void onRunFailed(String error) {
            // AI가 만든 임의 문자열이라 사용자 입력이 섞일 수 있다 — 원인 파악이 가능한 선에서 절단해 남긴다
            log.warn("AI run 실패. messageId={} error={}",
                    ids.assistantMessageId(), truncate(error, 200));
            failWith("AI_RUN_FAILED", "답변을 생성하지 못했습니다.", true);
        }

        @Override
        public void onTransportError(Exception cause) {
            if (cause instanceof TimeoutException) {
                // 어댑터의 idle timeout — 이벤트 사이 침묵이 상한을 넘었다
                log.warn("AI 스트림 침묵 초과. messageId={}", ids.assistantMessageId());
                cancelUpstream();
                failWith("AI_TIMEOUT", "답변 생성 시간이 초과되었습니다.", true);
                return;
            }
            if (finalContent != null) {
                // message.completed 후 run.completed 없이 종료 — 계약의 AI_PROTOCOL_ERROR
                log.warn("최종 답변 후 종료 신호 없이 스트림 종료. messageId={} causeType={}",
                        ids.assistantMessageId(), cause.getClass().getSimpleName());
                failWith("AI_PROTOCOL_ERROR", "답변 생성 결과가 올바르지 않습니다.", false);
                return;
            }
            log.warn("AI 스트림 단절. messageId={}", ids.assistantMessageId(), cause);
            failWith("AI_STREAM_DISCONNECTED", "답변 생성 연결이 끊어졌습니다.", true);
        }

        /** 종결 결정권을 이미 가진 쪽(onRunCompleted)이 부르는 실패 경로. */
        private void failLocked(String code, String message, boolean retryable) {
            try {
                transitions.fail(ids.assistantMessageId());
            } catch (RuntimeException e) {
                log.error("FAILED 전이조차 실패 — terminal 없이 종료. messageId={}",
                        ids.assistantMessageId(), e);
                emitter.completeWithError(e);
                return;
            }
            send("error", ChatSseEventData.StreamError.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), code, message, retryable));
            complete();
        }

        private void failWith(String code, String message, boolean retryable) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            failLocked(code, message, retryable);
        }

        private volatile long lastOutboundNanos = System.nanoTime();

        private void send(String eventName, Object payload) {
            if (!feConnected.get()) {
                return;
            }
            synchronized (sendLock) { // 타이머 스레드(heartbeat, Task 5)와 relay 스레드가 겹칠 수 있다
                try {
                    emitter.send(SseEmitter.event().name(eventName)
                            .data(payload, MediaType.APPLICATION_JSON));
                    lastOutboundNanos = System.nanoTime();
                } catch (IOException | IllegalStateException e) {
                    feConnected.set(false);
                    log.debug("FE 전송 중단 — 연결 종료로 판단. messageId={}", ids.assistantMessageId());
                }
            }
        }

        private void complete() {
            if (feConnected.get()) {
                emitter.complete();
            }
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…(절단)";
    }
}
