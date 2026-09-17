package com.ymc.paper.domain;

/** API의 knowledgeGraphStatus. 컴파일 상태와 viz.html 키 유무에서 계산하며 DB에 따로 저장하지 않는다. */
public enum KnowledgeGraphStatus {
    PENDING,
    READY,
    FAILED;

    /**
     * 컴파일 상태가 없으면 아직 요청 전이라 PENDING이다. COMPLETED여도 키가 없으면 산출물이 없는 것이라 FAILED —
     * manifest에 artifact가 빠진 경우와 SQL로 종결한 옛 문서가 여기 해당한다.
     */
    public static KnowledgeGraphStatus of(CompileStatus compileStatus, String knowledgeGraphKey) {
        if (compileStatus == null) {
            return PENDING;
        }
        return switch (compileStatus) {
            case REQUESTED -> PENDING;
            case COMPLETED -> knowledgeGraphKey == null || knowledgeGraphKey.isBlank() ? FAILED : READY;
            case FAILED -> FAILED;
        };
    }
}
