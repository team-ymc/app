package com.ymc.paper.service.port;

import java.util.UUID;

/**
 * 파싱 요청 발행(BE → AI) 포트. 조건부 전이에 성공한 호출만 발행한다 — 중복 complete는 재발행하지 않는다
 * (asyncapi.yaml `publishParseRequest`).
 */
public interface ParseRequestPublisher {

    /** 실패 시 예외를 던진다. 삼키면 레코드만 전이되고 파싱이 시작되지 않는다. */
    void publish(UUID paperId, String fileKey);
}
