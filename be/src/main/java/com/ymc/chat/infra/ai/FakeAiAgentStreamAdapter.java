package com.ymc.chat.infra.ai;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

/**
 * 고정 delta를 흘려보내는 fake 구현. YMC-256에서 SSE 성공 경로를 미리 검증하기 위한 것으로,
 * YMC-257의 WebClient 어댑터가 본 구현이 되면 테스트 스코프로 이동한다 (설계 §3).
 *
 * <p>run당 virtual thread 하나에서 콜백을 순서대로 호출한다 — 실제 어댑터와 같은
 * "controller 스레드 밖에서 이벤트가 온다"는 성질을 유지한다.
 */
@Component
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
