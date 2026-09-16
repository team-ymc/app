package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TranslationStatusTest {

    @ParameterizedTest(name = "language={0}, compile={1} → {2}")
    @CsvSource(nullValues = "null", value = {
            "en,   null,      PENDING",
            "en,   REQUESTED, PENDING",
            "en,   COMPLETED, READY",
            "en,   FAILED,    FAILED",
            "ko,   null,      NOT_APPLICABLE",
            "ko,   COMPLETED, NOT_APPLICABLE",
            "und,  FAILED,    NOT_APPLICABLE",
            "null, null,      NOT_APPLICABLE",
            "null, COMPLETED, NOT_APPLICABLE",
    })
    void 언어와_컴파일_상태로_번역_상태를_계산한다(String language, CompileStatus compile, TranslationStatus expected) {
        assertThat(TranslationStatus.of(language, compile)).isEqualTo(expected);
    }
}
