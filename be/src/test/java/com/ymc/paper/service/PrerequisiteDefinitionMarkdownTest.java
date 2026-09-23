package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrerequisiteDefinitionMarkdownTest {

    @Test
    void 고정_형식에서_영문과_국문을_꺼낸다() {
        String md = "### Self-attention\n\n**Definition (정의)**\n\n"
                + "A mechanism that relates each token to every other token  \n"
                + "각 토큰을 다른 모든 토큰과 연결하는 메커니즘";

        assertThat(PrerequisiteDefinitionMarkdown.parse(md)).contains(new PrerequisiteDefinition(
                "A mechanism that relates each token to every other token",
                "각 토큰을 다른 모든 토큰과 연결하는 메커니즘"));
    }

    @Test
    void 줄이_하나_더_붙으면_empty다() {
        String md = "### T\n\n**Definition (정의)**\n\nen  \nko\n\n추가 설명";
        assertThat(PrerequisiteDefinitionMarkdown.parse(md)).isEmpty();
    }

    @Test
    void 고정_문구가_다르면_empty다() {
        String md = "### T\n\n**Definition**\n\nen  \nko";
        assertThat(PrerequisiteDefinitionMarkdown.parse(md)).isEmpty();
    }

    @Test
    void null이나_빈_문자열은_empty다() {
        assertThat(PrerequisiteDefinitionMarkdown.parse(null)).isEmpty();
        assertThat(PrerequisiteDefinitionMarkdown.parse("")).isEmpty();
    }
}
