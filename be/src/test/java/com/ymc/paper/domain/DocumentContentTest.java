package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class DocumentContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();

    @Test
    void DocumentContent는_documentId와_시각이_필수다() {
        DocumentContent content =
                DocumentContent.of(DOCUMENT_ID, "Attention Is All You Need", 1, "en", Instant.now());
        assertThat(content.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(content.getSchemaVersion()).isEqualTo(1);
        assertThat(content.getSourceLanguage()).isEqualTo("en");

        assertThatThrownBy(() -> DocumentContent.of(null, "t", 1, "en", Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void title은_null을_허용한다_파서가_제목을_못_찾은_문서() {
        DocumentContent content = DocumentContent.of(DOCUMENT_ID, null, 1, "en", Instant.now());
        assertThat(content.getTitle()).isNull();
    }

    @Test
    void sourceLanguage는_null을_허용한다_번역_도입_전_패키지() {
        DocumentContent content = DocumentContent.of(DOCUMENT_ID, "t", 1, null, Instant.now());
        assertThat(content.getSourceLanguage()).isNull();
    }

    @Test
    void 블록은_blockId와_content가_필수다() {
        DocumentContentBlock block = DocumentContentBlock.of(DOCUMENT_ID, "p0000-b0002", 0, "doc_title", 1,
                List.of("p0000-b0002"), MAPPER.createObjectNode().put("format", "text").put("text", "제목"));
        assertThat(block.getBlockId()).isEqualTo("p0000-b0002");
        assertThat(block.getHeadingLevel()).isEqualTo(1);

        assertThatThrownBy(() -> DocumentContentBlock.of(DOCUMENT_ID, null, 0, "text", null, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 텍스트_블록에_번역을_병합하면_textKor가_붙고_원문은_그대로다() {
        DocumentContentBlock block = DocumentContentBlock.of(DOCUMENT_ID, "b0", 0, "text", null,
                List.of(), MAPPER.createObjectNode().put("format", "text").put("text", "Body"));

        assertThat(block.mergeTranslation("본문")).isTrue();
        assertThat(block.getContent().get("text").asText()).isEqualTo("Body");
        assertThat(block.getContent().get("textKor").asText()).isEqualTo("본문");

        // 같은 값을 다시 병합해도 결과가 같다 (재전달 멱등)
        assertThat(block.mergeTranslation("본문")).isTrue();
        assertThat(block.getContent().get("textKor").asText()).isEqualTo("본문");
    }

    @Test
    void 텍스트가_아닌_블록에는_병합하지_않는다() {
        DocumentContentBlock formula = DocumentContentBlock.of(DOCUMENT_ID, "f0", 0, "display_formula", null,
                List.of(), MAPPER.createObjectNode().put("format", "formula").put("tex", "E=mc^2"));

        assertThat(formula.mergeTranslation("번역")).isFalse();
        assertThat(formula.getContent().has("textKor")).isFalse();
    }

    @Test
    void asset은_key와_s3Key가_필수다() {
        DocumentContentAsset asset = DocumentContentAsset.of(DOCUMENT_ID, "image_0",
                "papers/x/assets/images/image_0.jpg", "image/jpeg");
        assertThat(asset.getAssetKey()).isEqualTo("image_0");

        assertThatThrownBy(() -> DocumentContentAsset.of(DOCUMENT_ID, "image_0", null, "image/jpeg"))
                .isInstanceOf(NullPointerException.class);
    }
}
