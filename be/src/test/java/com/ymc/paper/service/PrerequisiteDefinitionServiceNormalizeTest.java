package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrerequisiteDefinitionServiceNormalizeTest {

    @Test
    void NFC_공백_소문자_정규화() {
        String decomposed = "Café  Term ";  // e + combining acute
        assertThat(PrerequisiteDefinitionService.normalize(decomposed)).isEqualTo("café term");
    }

    @Test
    void 호환_문자는_합치지_않는다() {
        assertThat(PrerequisiteDefinitionService.normalize("x²")).isEqualTo("x²");
    }
}
