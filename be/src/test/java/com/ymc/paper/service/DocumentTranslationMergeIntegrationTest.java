package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.ymc.paper.domain.DocumentContentBlock;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ExtendWith(OutputCaptureExtension.class)
class DocumentTranslationMergeIntegrationTest extends IntegrationTest {

    @Autowired
    DocumentTranslationMergeService mergeService;

    /** COMPLETED + 사이드카 있는 패키지로 적재까지 끝난 논문. */
    private Paper givenIngestedTranslatedPaper() {
        Paper paper = givenProcessingPaper("merge.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        return reload(paper.getId());
    }

    private List<DocumentContentBlock> blocksOf(UUID documentId) {
        return documentContentBlockRepository.findAllByDocumentIdOrderByGlobalOrderAsc(documentId);
    }

    @Test
    void translated_블록에만_textKor가_붙는다() {
        Paper paper = givenIngestedTranslatedPaper();
        String manifestKey = "papers/" + paper.getId() + "/manifest.json";

        int merged = mergeService.merge(paper.getDocumentId(), manifestKey);

        assertThat(merged).isEqualTo(2);
        List<DocumentContentBlock> blocks = blocksOf(paper.getDocumentId());
        assertThat(blocks.get(0).getContent().has("textKor")).isFalse();   // doc_title
        assertThat(blocks.get(1).getContent().get("textKor").asText()).contains("새로운 구조");
        assertThat(blocks.get(2).getContent().get("textKor").asText()).contains("영어로 된 본문");
        assertThat(blocks.get(3).getContent().has("textKor")).isFalse();   // reference_content
        assertThat(blocks.get(4).getContent().has("textKor")).isFalse();   // image
    }

    @Test
    void 두_번_병합해도_결과가_같다() {
        Paper paper = givenIngestedTranslatedPaper();
        String manifestKey = "papers/" + paper.getId() + "/manifest.json";

        mergeService.merge(paper.getDocumentId(), manifestKey);
        int second = mergeService.merge(paper.getDocumentId(), manifestKey);

        assertThat(second).isEqualTo(2);
        assertThat(blocksOf(paper.getDocumentId()).get(1).getContent().get("textKor").asText()).contains("새로운 구조");
    }

    @Test
    void 적재에_없는_blockId와_텍스트가_아닌_블록은_건너뛰고_WARN이다(CapturedOutput output) {
        Paper paper = givenIngestedTranslatedPaper();
        String prefix = "papers/" + paper.getId() + "/";
        // 사이드카를 덮어쓴다: 모르는 id 하나, 이미지 블록(p0000-b0004) 하나, 정상 하나
        s3.putObject(PutObjectRequest.builder().bucket(awsProperties.s3().bucket())
                        .key(prefix + "frontend/translation-ko.json").build(),
                RequestBody.fromString("""
                        {"schema_version":1,"blocks":[
                          {"block_id":"p0000-b0099","translation_status":"translated","translated_block_content":{"format":"text","text_kor":"없는 블록"}},
                          {"block_id":"p0000-b0004","translation_status":"translated","translated_block_content":{"format":"text","text_kor":"이미지에 번역"}},
                          {"block_id":"p0000-b0002","translation_status":"translated","translated_block_content":{"format":"text","text_kor":"정상 병합"}}
                        ]}
                        """));

        int merged = mergeService.merge(paper.getDocumentId(), prefix + "manifest.json");

        assertThat(merged).isEqualTo(1);
        List<DocumentContentBlock> blocks = blocksOf(paper.getDocumentId());
        assertThat(blocks.get(2).getContent().get("textKor").asText()).isEqualTo("정상 병합");
        assertThat(blocks.get(4).getContent().has("textKor")).isFalse();
        assertThat(output.getOut()).contains("p0000-b0099").contains("p0000-b0004");
    }

    @Test
    void 사이드카가_없으면_0건이고_블록은_그대로다() {
        Paper paper = givenProcessingPaper("no-sidecar.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenPackageOnS3(paper.getId());   // 사이드카 항목 없는 manifest
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);

        assertThat(mergeService.merge(paper.getDocumentId(), manifestKey)).isZero();
        assertThat(blocksOf(paper.getDocumentId())).allSatisfy(b -> assertThat(b.getContent().has("textKor")).isFalse());
    }
}
