package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.ymc.paper.domain.DocumentPrerequisiteHighlight;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ExtendWith(OutputCaptureExtension.class)
class DocumentPrerequisiteHighlightIngestIntegrationTest extends IntegrationTest {

    @Autowired
    DocumentPrerequisiteHighlightIngestService ingestService;

    /** COMPLETED + 선행지식 사이드카 있는 패키지로 본문 적재까지 끝난 논문. */
    private Paper givenIngestedPaper() {
        Paper paper = givenProcessingPaper("highlight.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        return reload(paper.getId());
    }

    private List<DocumentPrerequisiteHighlight> highlightsOf(UUID documentId) {
        return documentPrerequisiteHighlightRepository.findAllByDocumentIdOrderByIdAsc(documentId);
    }

    private void putSidecar(Paper paper, String json) {
        s3.putObject(PutObjectRequest.builder().bucket(awsProperties.s3().bucket())
                        .key("papers/" + paper.getId() + "/frontend/prerequisite-highlights.json").build(),
                RequestBody.fromString(json));
    }

    @Test
    void 사이드카의_하이라이트를_문서_순서대로_적재한다() {
        Paper paper = givenIngestedPaper();

        int ingested = ingestService.ingest(paper.getDocumentId(), "papers/" + paper.getId() + "/manifest.json");

        assertThat(ingested).isEqualTo(2);
        assertThat(highlightsOf(paper.getDocumentId()))
                .extracting(DocumentPrerequisiteHighlight::getHighlightId, DocumentPrerequisiteHighlight::getBlockId,
                        DocumentPrerequisiteHighlight::getStartOffset, DocumentPrerequisiteHighlight::getEndOffset,
                        DocumentPrerequisiteHighlight::getText)
                .containsExactly(
                        tuple("prerequisite-0001", "p0000-b0001", 13, 29, "new architecture"),
                        tuple("prerequisite-0002", "p0000-b0002", 18, 25, "English"));
    }

    @Test
    void 다시_적재하면_이전_행을_교체한다() {
        Paper paper = givenIngestedPaper();
        String manifestKey = "papers/" + paper.getId() + "/manifest.json";
        ingestService.ingest(paper.getDocumentId(), manifestKey);
        putSidecar(paper, """
                {"schema_version":1,"offset_encoding":"utf-16","highlights":[
                  {"highlight_id":"prerequisite-0001","block_id":"p0000-b0002","start_offset":0,"end_offset":4,"text":"Body"}
                ]}
                """);

        int second = ingestService.ingest(paper.getDocumentId(), manifestKey);

        assertThat(second).isEqualTo(1);
        assertThat(highlightsOf(paper.getDocumentId()))
                .extracting(DocumentPrerequisiteHighlight::getText).containsExactly("Body");
    }

    @Test
    void 적재된_본문에_없는_blockId는_건너뛰고_WARN이다(CapturedOutput output) {
        Paper paper = givenIngestedPaper();
        putSidecar(paper, """
                {"schema_version":1,"offset_encoding":"utf-16","highlights":[
                  {"highlight_id":"prerequisite-0001","block_id":"p0000-b0099","start_offset":0,"end_offset":4,"text":"Gone"},
                  {"highlight_id":"prerequisite-0002","block_id":"p0000-b0002","start_offset":0,"end_offset":4,"text":"Body"}
                ]}
                """);

        int ingested = ingestService.ingest(paper.getDocumentId(), "papers/" + paper.getId() + "/manifest.json");

        assertThat(ingested).isEqualTo(1);
        assertThat(highlightsOf(paper.getDocumentId()))
                .extracting(DocumentPrerequisiteHighlight::getHighlightId).containsExactly("prerequisite-0002");
        assertThat(output.getOut()).contains("p0000-b0099");
    }

    @Test
    void 본문_글자와_다른_하이라이트는_건너뛰고_WARN이다(CapturedOutput output) {
        Paper paper = givenIngestedPaper();
        putSidecar(paper, """
                {"schema_version":1,"offset_encoding":"utf-16","highlights":[
                  {"highlight_id":"prerequisite-0001","block_id":"p0000-b0002","start_offset":0,"end_offset":4,"text":"Para"},
                  {"highlight_id":"prerequisite-0002","block_id":"p0000-b0002","start_offset":5,"end_offset":14,"text":"paragraph"}
                ]}
                """);

        int ingested = ingestService.ingest(paper.getDocumentId(), "papers/" + paper.getId() + "/manifest.json");

        assertThat(ingested).isEqualTo(1);
        assertThat(highlightsOf(paper.getDocumentId()))
                .extracting(DocumentPrerequisiteHighlight::getHighlightId).containsExactly("prerequisite-0002");
        assertThat(output.getOut()).contains("prerequisite-0001");
    }

    @Test
    void 사이드카가_없는_패키지는_0건이다() {
        Paper paper = givenProcessingPaper("no-highlight.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenPackageOnS3(paper.getId());
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);

        assertThat(ingestService.ingest(paper.getDocumentId(), manifestKey)).isZero();
        assertThat(highlightsOf(paper.getDocumentId())).isEmpty();
    }
}
