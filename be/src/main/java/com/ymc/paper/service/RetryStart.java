package com.ymc.paper.service;

import java.util.UUID;

import com.ymc.paper.domain.Document;

/** 재시도 트랜잭션이 커밋 뒤 호출자에게 넘기는 다음 행동. 큐 발행은 트랜잭션 밖에서 한다. */
public record RetryStart(Kind kind, UUID documentId, UUID requestPaperId, String fileKey) {

    public enum Kind {
        /** 이미 완료된 결과에 연결해 확정까지 끝났다. */
        SETTLED,
        /** 진행 중인 처리의 결과를 기다린다. */
        WAITING,
        /** 파싱 요청을 발행해야 한다. */
        PUBLISH_PARSE,
        /** 컴파일 요청을 발행해야 한다. */
        PUBLISH_COMPILE
    }

    static RetryStart of(Kind kind, Document document) {
        return new RetryStart(
                kind, document.getId(), document.getRequestPaperId(), document.getFileKey());
    }
}
