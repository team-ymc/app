package com.ymc.paper.domain;

/** 지식 컴파일 단계의 상태. null은 아직 요청 전이다. 파싱 상태와 별개로 흐른다. */
public enum CompileStatus {
    REQUESTED,
    COMPLETED,
    FAILED
}
