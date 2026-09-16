package com.ymc.paper.domain;

/** API의 translationStatus. 문서 언어와 컴파일 상태에서 계산하며 DB에 따로 저장하지 않는다. */
public enum TranslationStatus {
    NOT_APPLICABLE,
    PENDING,
    READY,
    FAILED;

    /**
     * 영어가 아니면 번역 대상이 아니다. 영어인데 컴파일 상태가 없으면 아직 요청 전이라 PENDING이다 —
     * 발행 실패 뒤 재전달을 기다리는 창에서 FAILED로 답하면 FE가 폴링을 멈춘다.
     */
    public static TranslationStatus of(String sourceLanguage, CompileStatus compileStatus) {
        if (!"en".equals(sourceLanguage)) {
            return NOT_APPLICABLE;
        }
        if (compileStatus == null) {
            return PENDING;
        }
        return switch (compileStatus) {
            case REQUESTED -> PENDING;
            case COMPLETED -> READY;
            case FAILED -> FAILED;
        };
    }
}
