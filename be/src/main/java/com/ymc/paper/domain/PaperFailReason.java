package com.ymc.paper.domain;

/** API의 failReason. 내부 실패 코드 대신 재시도 가능 여부만 드러낸다. */
public enum PaperFailReason {

    /** 분석에 실패함. 재시도할 수 있다. */
    PROCESSING_FAILED,

    /** 같은 파일의 시도 횟수를 모두 씀. 재시도할 수 없다. */
    RETRY_LIMIT_EXCEEDED
}
