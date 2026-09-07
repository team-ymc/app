package com.ymc.chat.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ymc.chat.api.dto.ChatSelectionDto;
import com.ymc.chat.api.dto.TranslationSseEventData;
import com.ymc.chat.infra.ai.ChatStreamProperties;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.chat.service.port.AiTranslateRequest;
import com.ymc.chat.service.port.AiTranslateStreamPort;

import lombok.RequiredArgsConstructor;

/**
 * 인라인 번역 스트리밍 조율 — translation.started 전송, AI 구독, delta 중계, 종결 시 상태 확정과
 * terminal event 전송. 골격·타이머·상한은 {@link ChatStreamService}와 같다.
 *
 * <p>FE 연결이 끊겨도 upstream 소비와 최종 저장은 계속한다 — emitter 전송만 스킵한다.
 */
@Service
@RequiredArgsConstructor
public class TranslationStreamService {

    private static final Logger log = LoggerFactory.getLogger(TranslationStreamService.class);

    private final AiTranslateStreamPort aiTranslateStreamPort;
    private final TranslationRunTransitions transitions;
    private final ChatStreamProperties chatStreamProperties;
    private final ScheduledExecutorService chatTimerExecutor;
    private final ExecutorService chatRelayExecutor;

    /** translation.started를 보내고 AI 스트림을 시작한다. 호출 시점은 시작 트랜잭션 commit 후다. */
    public void begin(SseEmitter emitter, TranslationStartResult started, ChatSelectionDto selection) {
        Run run = new Run(emitter, started);
        try {
            run.sendStarted();
            AiRunHandle handle = aiTranslateStreamPort.stream(
                    new AiTranslateRequest(started.translationId().toString(), started.aiPaperId(), selection), run);
            run.arm(handle);
        } catch (RuntimeException e) {
            run.cancelUpstream();
            run.onTransportError(e);
        }
    }

    /** 한 스트림의 상태. 어댑터가 콜백을 직렬 호출하므로 필드 동기화는 FE 단절 플래그만 필요하다. */
    private class Run implements AiStreamListener {

        private final SseEmitter emitter;
        private final TranslationStartResult ids;
        private final AtomicBoolean feConnected = new AtomicBoolean(true);
        private final StringBuilder accumulated = new StringBuilder();
        private String finalContent;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final ReentrantLock sendLock = new ReentrantLock();
        private volatile AiRunHandle handle;
        private volatile ScheduledFuture<?> deadlineTask;
        private volatile ScheduledFuture<?> heartbeatTask;
        private volatile long lastOutboundNanos = System.nanoTime();

        private Run(SseEmitter emitter, TranslationStartResult ids) {
            this.emitter = emitter;
            this.ids = ids;
            emitter.onCompletion(() -> feConnected.set(false));
            emitter.onError(t -> feConnected.set(false));
            emitter.onTimeout(() -> feConnected.set(false));
        }

        void arm(AiRunHandle handle) {
            this.handle = handle;
            if (finished.get()) {
                return;
            }
            deadlineTask = chatTimerExecutor.schedule(
                    this::onDeadlineExceeded,
                    chatStreamProperties.deadline().toMillis(), TimeUnit.MILLISECONDS);
            long interval = chatStreamProperties.heartbeatInterval().toMillis();
            heartbeatTask = chatTimerExecutor.scheduleAtFixedRate(
                    this::maybeSendHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
            if (finished.get()) {
                cancelTimers();
            }
        }

        private void onDeadlineExceeded() {
            delegate(() -> {
                log.warn("translation deadline 초과. translationId={}", ids.translationId());
                cancelUpstream();
                failWith("AI_TIMEOUT", "번역 생성 시간이 초과되었습니다.", true);
            });
        }

        private void maybeSendHeartbeat() {
            if (finished.get() || !feConnected.get()) {
                return;
            }
            if (System.nanoTime() - lastOutboundNanos < chatStreamProperties.heartbeatInterval().toNanos()) {
                return;
            }
            delegate(() -> {
                if (finished.get() || !feConnected.get()) {
                    return;
                }
                send("heartbeat", TranslationSseEventData.Heartbeat.of(ids.paperId(), ids.translationId()));
            });
        }

        /** 타이머 스레드에서는 위임만 — emitter.send·DB는 relay executor(virtual thread)에서. */
        private void delegate(Runnable work) {
            try {
                chatRelayExecutor.execute(work);
            } catch (RejectedExecutionException e) {
                log.debug("셧다운 중 타이머 위임 거부됨. translationId={}", ids.translationId());
            }
        }

        private void cancelTimers() {
            ScheduledFuture<?> d = deadlineTask;
            if (d != null) {
                d.cancel(false);
            }
            ScheduledFuture<?> h = heartbeatTask;
            if (h != null) {
                h.cancel(false);
            }
        }

        private void cancelUpstream() {
            AiRunHandle current = handle;
            if (current != null) {
                current.cancel();
            }
        }

        private void sendStarted() {
            send("translation.started", TranslationSseEventData.Started.of(ids.paperId(), ids.translationId()));
        }

        @Override
        public void onRunStarted() {
            // BE 내부 확인용 — FE에는 보내지 않는다
        }

        @Override
        public void onDelta(String delta) {
            if (finished.get()) {
                return;
            }
            if (accumulated.length() + delta.length() > chatStreamProperties.maxContentLength()) {
                log.warn("delta 누적 상한 초과. translationId={} 누적={}자", ids.translationId(), accumulated.length());
                cancelUpstream();
                failWith("AI_RESPONSE_TOO_LARGE", "번역이 허용 길이를 초과했습니다.", false);
                return;
            }
            accumulated.append(delta);
            send("translation.delta", TranslationSseEventData.Delta.of(ids.paperId(), ids.translationId(), delta));
        }

        @Override
        public void onMessageCompleted(String message) {
            this.finalContent = message; // 아직 성공 아님 — run.completed 대기
        }

        @Override
        public void onRunCompleted(BigDecimal estimatedCostUsd) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            cancelTimers();
            if (finalContent == null) {
                failLocked("AI_PROTOCOL_ERROR", "번역 결과가 올바르지 않습니다.", false);
                return;
            }
            if (!accumulated.toString().equals(finalContent)) {
                log.warn("누적 delta와 최종 번역이 다릅니다. translationId={} 누적={}자 최종={}자",
                        ids.translationId(), accumulated.length(), finalContent.length());
            }
            boolean committed;
            try {
                committed = transitions.complete(ids.translationId(), finalContent, estimatedCostUsd);
            } catch (RuntimeException e) {
                log.error("최종 번역 저장 실패. translationId={}", ids.translationId(), e);
                failLocked("TRANSLATION_PERSISTENCE_FAILED", "번역을 저장하지 못했습니다.", true);
                return;
            }
            if (!committed) {
                complete(); // 이미 다른 경로가 FAILED로 확정 — 성공 event를 보내지 않는다
                return;
            }
            send("translation.completed",
                    TranslationSseEventData.Completed.of(ids.paperId(), ids.translationId(), finalContent));
            complete();
        }

        @Override
        public void onRunFailed(String code, String message) {
            log.warn("AI 번역 run 실패. translationId={} code={} message={}",
                    ids.translationId(), code, truncate(message, 200));
            switch (code) {
                case "SELECTION_BLOCK_NOT_FOUND", "SELECTION_RANGE_INVALID", "SELECTION_OFFSET_INVALID" ->
                        failWith("SELECTION_INVALID", "번역할 수 없는 선택 영역입니다.", false);
                case "SELECTION_TOO_LARGE" ->
                        failWith("SELECTION_TOO_LARGE", "선택 영역이 너무 큽니다.", false);
                default -> failWith("AI_RUN_FAILED", "번역을 생성하지 못했습니다.", true);
            }
        }

        @Override
        public void onTransportError(Exception cause) {
            if (cause instanceof TimeoutException) {
                log.warn("AI 번역 스트림 침묵 초과. translationId={}", ids.translationId());
                cancelUpstream();
                failWith("AI_TIMEOUT", "번역 생성 시간이 초과되었습니다.", true);
                return;
            }
            if (finalContent != null) {
                log.warn("최종 번역 후 종료 신호 없이 스트림 종료. translationId={} causeType={}",
                        ids.translationId(), cause.getClass().getSimpleName());
                failWith("AI_PROTOCOL_ERROR", "번역 결과가 올바르지 않습니다.", false);
                return;
            }
            log.warn("AI 번역 스트림 단절. translationId={}", ids.translationId(), cause);
            failWith("AI_STREAM_DISCONNECTED", "번역 생성 연결이 끊어졌습니다.", true);
        }

        private void failLocked(String code, String message, boolean retryable) {
            cancelTimers();
            try {
                transitions.fail(ids.translationId());
            } catch (RuntimeException e) {
                log.error("FAILED 전이조차 실패 — terminal 없이 종료. translationId={}", ids.translationId(), e);
                emitter.completeWithError(e);
                return;
            }
            send("error", TranslationSseEventData.StreamError.of(
                    ids.paperId(), ids.translationId(), code, message, retryable));
            complete();
        }

        private void failWith(String code, String message, boolean retryable) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            failLocked(code, message, retryable);
        }

        private void send(String eventName, Object payload) {
            if (!feConnected.get()) {
                return;
            }
            sendLock.lock(); // synchronized는 virtual thread를 pinning한다 — ReentrantLock은 아니다
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
                lastOutboundNanos = System.nanoTime();
            } catch (IOException | IllegalStateException e) {
                feConnected.set(false);
                log.debug("FE 전송 중단 — 연결 종료로 판단. translationId={}", ids.translationId());
            } finally {
                sendLock.unlock();
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
