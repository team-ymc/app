package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class PaperContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID PAPER_ID = UUID.randomUUID();

    @Test
    void PaperContent는_paperId와_시각이_필수다() {
        PaperContent content = PaperContent.of(PAPER_ID, "Attention Is All You Need", 1, Instant.now());
        assertThat(content.getPaperId()).isEqualTo(PAPER_ID);
        assertThat(content.getSchemaVersion()).isEqualTo(1);

        assertThatThrownBy(() -> PaperContent.of(null, "t", 1, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void title은_null을_허용한다_파서가_제목을_못_찾은_문서() {
        PaperContent content = PaperContent.of(PAPER_ID, null, 1, Instant.now());
        assertThat(content.getTitle()).isNull();
    }

    @Test
    void 블록은_blockId와_content가_필수다() {
        PaperContentBlock block = PaperContentBlock.of(PAPER_ID, "p0000-b0002", 0, "doc_title", 1,
                List.of("p0000-b0002"), MAPPER.createObjectNode().put("format", "text").put("text", "제목"));
        assertThat(block.getBlockId()).isEqualTo("p0000-b0002");
        assertThat(block.getHeadingLevel()).isEqualTo(1);

        assertThatThrownBy(() -> PaperContentBlock.of(PAPER_ID, null, 0, "text", null, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void asset은_key와_s3Key가_필수다() {
        PaperContentAsset asset = PaperContentAsset.of(PAPER_ID, "image_0",
                "papers/x/assets/images/image_0.jpg", "image/jpeg");
        assertThat(asset.getAssetKey()).isEqualTo("image_0");

        assertThatThrownBy(() -> PaperContentAsset.of(PAPER_ID, "image_0", null, "image/jpeg"))
                .isInstanceOf(NullPointerException.class);
    }
}
