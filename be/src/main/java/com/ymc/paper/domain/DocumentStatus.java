package com.ymc.paper.domain;

/**
 * Document의 파싱 라이프사이클. 검증된 객체가 있어야 Document가 생기므로 UPLOAD_PENDING이 없다.
 * API의 PaperStatus로는 이름 그대로 매핑된다.
 */
public enum DocumentStatus {
    UPLOADED,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
