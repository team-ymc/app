package com.ymc.chat.service.port;

/** AI inline-translate 스트리밍 호출 포트. 인터페이스는 service/, 구현은 infra/. reactive 타입을 노출하지 않는다. */
public interface AiTranslateStreamPort {

    /** 스트림을 시작하고 즉시 반환한다. 이벤트는 어댑터의 스레드에서 listener로 전달된다. */
    AiRunHandle stream(AiTranslateRequest request, AiStreamListener listener);
}
