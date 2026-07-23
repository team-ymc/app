package com.ymc.chat.service.port;

/** 진행 중인 AI run의 취소 손잡이. YMC-257의 timeout 워치독이 사용한다. */
public interface AiRunHandle {

    /** upstream 연결을 끊어 AI의 생성 취소를 유도한다. 중복 호출은 무해해야 한다. */
    void cancel();
}
