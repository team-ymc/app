package com.ymc.chat.infra.ai;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.chat.service.port.AiTranslateRequest;
import com.ymc.chat.service.port.AiTranslateStreamPort;

/** ai.fake-stream=true일 때의 번역 fake. AI 서버 없이 SSE 경로를 검증한다. */
@Component
@ConditionalOnProperty(name = "ai.fake-stream", havingValue = "true")
public class FakeAiTranslateStreamAdapter implements AiTranslateStreamPort {

    static final List<String> DELTAS = List.of("가짜 ", "번역", "입니다.");

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public AiRunHandle stream(AiTranslateRequest request, AiStreamListener listener) {
        executor.execute(() -> {
            listener.onRunStarted();
            StringBuilder full = new StringBuilder();
            for (String delta : DELTAS) {
                full.append(delta);
                listener.onDelta(delta);
            }
            listener.onMessageCompleted(full.toString());
            listener.onRunCompleted(null);
        });
        return () -> {
            // fake는 즉시 완료되므로 취소할 것이 없다
        };
    }
}
