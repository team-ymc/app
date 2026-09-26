package com.ymc.chat.service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 열려 있는 SSE 연결 수. 실행 수(chat.active.runs)와 달리 FE가 떠나면 emitter가 닫히며 즉시 줄어든다.
 * 채팅·인라인 번역을 stream tag로 나눈다.
 */
@Component
public class SseConnectionMetrics {

    public static final String CHAT = "chat";
    public static final String TRANSLATION = "translation";

    private final Map<String, AtomicInteger> open = Map.of(CHAT, new AtomicInteger(), TRANSLATION, new AtomicInteger());

    public SseConnectionMetrics(MeterRegistry registry) {
        open.forEach((stream, count) -> Gauge.builder("sse.connections", count, AtomicInteger::get)
                .tag("stream", stream).register(registry));
    }

    /** emitter가 완료·오류·타임아웃 어느 경로로 닫혀도 한 번만 감소한다. */
    public void track(SseEmitter emitter, String stream) {
        Runnable close = opened(stream);
        emitter.onCompletion(close);
        emitter.onError(t -> close.run());
        emitter.onTimeout(close);
    }

    /** +1 하고, 여러 번 불러도 한 번만 -1 하는 닫기 손잡이를 돌려준다. */
    Runnable opened(String stream) {
        AtomicInteger count = open.get(stream);
        count.incrementAndGet();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                count.decrementAndGet();
            }
        };
    }
}
