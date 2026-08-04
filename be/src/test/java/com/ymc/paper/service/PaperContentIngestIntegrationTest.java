package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.ymc.common.config.AwsProperties;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperContentBlock;
import com.ymc.paper.domain.PaperContentBlockRepository;
import com.ymc.paper.domain.PaperContentRepository;
import com.ymc.paper.domain.PaperContentAssetRepository;
import com.ymc.support.IntegrationTest;

class PaperContentIngestIntegrationTest extends IntegrationTest {

    @Autowired
    PaperContentIngestService ingestService;

    @Autowired
    PaperContentRepository contentRepository;

    @Autowired
    PaperContentBlockRepository blockRepository;

    @Autowired
    PaperContentAssetRepository assetRepository;

    @Autowired
    AwsProperties awsProperties;

    @Test
    void 패키지를_적재하면_헤더_블록_asset이_저장된다() {
        Paper paper = givenProcessingPaper("ingest.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        ingestService.ingest(paper.getId(), manifestKey);

        assertThat(ingestService.isIngested(paper.getId())).isTrue();
        assertThat(contentRepository.findById(paper.getId()).orElseThrow().getTitle())
                .isEqualTo("Fixture Paper Title");

        List<PaperContentBlock> blocks = blockRepository.findAllByPaperIdOrderByGlobalOrderAsc(paper.getId());
        assertThat(blocks).hasSize(10);
        assertThat(blocks.get(4).getContent().get("tex").asText()).contains("softmax");
        assertThat(blocks.get(5).getContent().get("html").asText()).contains("<table>");

        assertThat(assetRepository.findAllByPaperId(paper.getId()))
                .extracting("assetKey").containsExactlyInAnyOrder("image_0", "image_1");
    }

    @Test
    void 재적재는_중복_없이_대체된다_멱등() {
        Paper paper = givenProcessingPaper("reingest.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        ingestService.ingest(paper.getId(), manifestKey);
        ingestService.ingest(paper.getId(), manifestKey);

        assertThat(blockRepository.findAllByPaperIdOrderByGlobalOrderAsc(paper.getId())).hasSize(10);
        assertThat(assetRepository.findAllByPaperId(paper.getId())).hasSize(2);
        assertThat(contentRepository.count()).isEqualTo(1);
    }

    @Test
    void 적재_전에는_isIngested가_false다() {
        Paper paper = givenProcessingPaper("not-yet.pdf");
        assertThat(ingestService.isIngested(paper.getId())).isFalse();
    }

    @Test
    void 적재_중간_실패는_부분_적재를_남기지_않는다() {
        Paper paper = givenProcessingPaper("rollback.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());
        givenDuplicateBlockFrontendDocumentOnS3(paper.getId());

        assertThatThrownBy(() -> ingestService.ingest(paper.getId(), manifestKey))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(ingestService.isIngested(paper.getId())).isFalse();
        assertThat(blockRepository.findAllByPaperIdOrderByGlobalOrderAsc(paper.getId())).isEmpty();
        assertThat(assetRepository.findAllByPaperId(paper.getId())).isEmpty();
    }

    /** block_id가 중복된 frontend 문서로 덮어써 삽입 단계(유니크 제약)에서 실패를 일으킨다. */
    private void givenDuplicateBlockFrontendDocumentOnS3(UUID paperId) {
        String duplicated = """
                {"schema_version":1,"assets":{},"blocks":[
                  {"block_id":"dup-0","global_block_order":0,"block_label":"text","heading_level":null,"section_path":[],"block_content":{"format":"text","text":"a"}},
                  {"block_id":"dup-0","global_block_order":1,"block_label":"text","heading_level":null,"section_path":[],"block_content":{"format":"text","text":"b"}}
                ]}
                """;
        s3.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                        .bucket(awsProperties.s3().bucket())
                        .key("papers/" + paperId + "/frontend/document.json")
                        .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(
                        duplicated.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
