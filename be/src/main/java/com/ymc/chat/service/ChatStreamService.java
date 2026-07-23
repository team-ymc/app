package com.ymc.chat.service;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ymc.chat.api.dto.ChatSseEventData;
import com.ymc.chat.service.port.AiAgentStreamPort;
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

    /** message.started를 보내고 AI 스트림을 시작한다. 호출 시점은 시작 트랜잭션 commit 후다. */
    public void begin(SseEmitter emitter, ChatStartResult started, String userContent) {
        Run run = new Run(emitter, started);
        run.sendStarted();
        aiAgentStreamPort.stream(
                new AiRunRequest(started.sessionId().toString(), userContent), run);
    }

    /** 한 스트림의 상태. 어댑터가 콜백을 직렬 호출하므로 필드 동기화는 FE 단절 플래그만 필요하다. */
    private class Run implements AiStreamListener {

        private final SseEmitter emitter;
        private final ChatStartResult ids;
        private final AtomicBoolean feConnected = new AtomicBoolean(true);
        private final StringBuilder accumulated = new StringBuilder();
        private String finalContent;

        private Run(SseEmitter emitter, ChatStartResult ids) {
            this.emitter = emitter;
            this.ids = ids;
            emitter.onCompletion(() -> feConnected.set(false));
            emitter.onError(t -> feConnected.set(false));
            emitter.onTimeout(() -> feConnected.set(false));
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
            if (finalContent == null) {
                // message.completed 없이 run.completed — 계약의 AI_PROTOCOL_ERROR
                failWith("AI_PROTOCOL_ERROR", "답변 생성 결과가 올바르지 않습니다.", false);
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
                failWith("MESSAGE_PERSISTENCE_FAILED", "답변을 저장하지 못했습니다.", true);
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
            log.warn("AI 스트림 단절. messageId={}", ids.assistantMessageId(), cause);
            failWith("AI_STREAM_DISCONNECTED", "답변 생성 연결이 끊어졌습니다.", true);
        }

        /** FAILED 확정 후 terminal error 전송. 저장 실패 시 terminal 없이 닫는다 (계약). */
        private void failWith(String code, String message, boolean retryable) {
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

        private void send(String eventName, Object payload) {
            if (!feConnected.get()) {
                return; // FE만 끊긴 것 — upstream 소비·저장은 계속 (계약 frontendDisconnect)
            }
            try {
                emitter.send(SseEmitter.event().name(eventName)
                        .data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                feConnected.set(false);
                log.debug("FE 전송 중단 — 연결 종료로 판단. messageId={}", ids.assistantMessageId());
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
