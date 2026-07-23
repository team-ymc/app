package com.ymc.chat.service.port;

/**
 * AI 에이전트 스트리밍 호출 포트. 외부 시스템(AI 서버) 격리 — 인터페이스는 service/,
 * 구현은 infra/ (be/CLAUDE.md). YMC-256은 fake, YMC-257이 WebClient 구현으로 교체한다.
 *
 * <p>reactive 타입을 노출하지 않는다 — service 계층은 동기 콜백 모델을 유지한다 (설계 §3 D4).
 */
public interface AiAgentStreamPort {

    /** 스트림을 시작하고 즉시 반환한다. 이벤트는 어댑터의 스레드에서 listener로 전달된다. */
    AiRunHandle stream(AiRunRequest request, AiStreamListener listener);
}
