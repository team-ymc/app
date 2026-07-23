package com.ymc.chat.infra.ai;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

/**
 * 고정 delta를 흘려보내는 fake 구현. ai.fake-stream=true일 때만 등록된다 —
 * 자동화 테스트가 AI 서버·LLM key 없이 SSE 경로를 검증하기 위한 것으로,
 * 실제 어댑터는 {@link AiAgentWebClientAdapter}다 (YMC-257).
 *
 * <p>run당 virtual thread 하나에서 콜백을 순서대로 호출한다 — 실제 어댑터와 같은
 * "controller 스레드 밖에서 이벤트가 온다"는 성질을 유지한다.
 */
@Component
@ConditionalOnProperty(name = "ai.fake-stream", havingValue = "true")
public class FakeAiAgentStreamAdapter implements AiAgentStreamPort {

    static final List<String> DELTAS = List.of("가짜 ", "응답", "입니다.");

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public AiRunHandle stream(AiRunRequest request, AiStreamListener listener) {
        executor.execute(() -> {
            listener.onRunStarted();
            StringBuilder full = new StringBuilder();
            for (String delta : DELTAS) {
                full.append(delta);
                listener.onDelta(delta);
            }
            listener.onMessageCompleted(full.toString());
            listener.onRunCompleted();
        });
        return () -> {
            // fake는 즉시 완료되므로 취소할 것이 없다
        };
    }
}
