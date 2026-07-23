package com.ymc.chat.service.port;

/**
 * AI 스트림 이벤트 수신 콜백. 구현체(어댑터)는 한 run의 콜백을 순서대로, 한 번에 하나씩
 * 호출해야 한다 — 리스너 쪽에서 동기화를 추가하지 않는다.
 *
 * <p>terminal은 {@code onRunCompleted / onRunFailed / onTransportError} 중 정확히 하나다.
 */
public interface AiStreamListener {

    void onRunStarted();

    /** assistant 응답의 부분 문자열. 완전한 단어·문장·Markdown block이라고 가정하지 않는다. */
    void onDelta(String delta);

    /** AI가 만든 최종 답변 전문. 이 시점은 아직 성공 확정이 아니다 (run.completed 대기). */
    void onMessageCompleted(String message);

    void onRunCompleted();

    /** AI가 run.failed를 보냄. raw error는 FE에 노출하지 않는다. */
    void onRunFailed(String error);

    /** terminal event 없이 연결이 끊기거나 스트림 소비 중 예외가 남. */
    void onTransportError(Exception cause);
}
