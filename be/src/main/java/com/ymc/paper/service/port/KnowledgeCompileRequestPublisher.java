package com.ymc.paper.service.port;

import java.util.UUID;

/** 지식 컴파일 요청 발행(BE → AI) 포트. 컴파일 선점 CAS에 성공한 호출만 발행한다. */
public interface KnowledgeCompileRequestPublisher {

    /** 실패 시 예외를 던진다. 삼키면 REQUESTED만 남고 컴파일이 시작되지 않는다. */
    void publish(UUID paperId, String manifestKey);
}
