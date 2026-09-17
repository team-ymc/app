package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentContentBlock;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.KnowledgeGraphStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.TranslationStatus;
import com.ymc.support.IntegrationTest;

/** knowledge-compile-results 소비. LocalStack에 실제로 발행하고 리스너가 소비하게 둔다. */
class KnowledgeCompileResultConsumptionIntegrationTest extends IntegrationTest {

    /** 파싱 완료·적재·컴파일 요청(REQUESTED)까지 끝난 영어 논문. 사이드카는 S3에 이미 있다. */
    private Paper givenRequestedPaper(String filename) {
        Paper paper = givenProcessingPaper(filename);
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        documentTransitions.markCompileRequested(paper.getDocumentId());
        return reload(paper.getId());
    }

    private String manifestKeyOf(Paper paper) {
        return "papers/" + paper.getId() + "/manifest.json";
    }

    private Document documentOf(Paper paper) {
        return documentRepository.findByRequestPaperId(paper.getId()).orElseThrow();
    }

    private List<DocumentContentBlock> blocksOf(Paper paper) {
        return documentContentBlockRepository.findAllByDocumentIdOrderByGlobalOrderAsc(paper.getDocumentId());
    }

    private void awaitCompileStatus(UUID requestPaperId, CompileStatus expected) {
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(documentRepository.findByRequestPaperId(requestPaperId)
                        .orElseThrow().getCompileStatus()).isEqualTo(expected));
    }

    @Test
    @DisplayName("completed: 사이드카를 블록에 병합하고 COMPLETED → 번역 상태 READY")
    void completedMergesAndMarksReady() {
        Paper paper = givenRequestedPaper("compile-ok.pdf");

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKeyOf(paper)));

        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
        Document document = documentOf(paper);
        assertThat(document.getCompileErrorCode()).isNull();
        assertThat(document.translationStatus()).isEqualTo(TranslationStatus.READY);
        assertThat(document.getKnowledgeGraphKey())
                .isEqualTo("papers/" + paper.getId() + "/knowledge-bundle/viz.html");
        assertThat(document.knowledgeGraphStatus()).isEqualTo(KnowledgeGraphStatus.READY);
        assertThat(blocksOf(paper).get(1).getContent().get("textKor").asText()).contains("새로운 구조");
        assertThat(blocksOf(paper).get(3).getContent().has("textKor")).isFalse();
    }

    @Test
    @DisplayName("failed: FAILED와 코드를 기록하고 파싱 상태·블록은 그대로")
    void failedRecordsCodeOnly() {
        Paper paper = givenRequestedPaper("compile-failed.pdf");

        publishCompileResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSED_DOCUMENT_INVALID","message":"x"}}
                """.formatted(paper.getId()));

        awaitCompileStatus(paper.getId(), CompileStatus.FAILED);
        Document document = documentOf(paper);
        assertThat(document.getCompileErrorCode()).isEqualTo("PARSED_DOCUMENT_INVALID");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(document.translationStatus()).isEqualTo(TranslationStatus.FAILED);
        assertThat(document.getKnowledgeGraphKey()).isNull();
        assertThat(document.knowledgeGraphStatus()).isEqualTo(KnowledgeGraphStatus.FAILED);
        assertThat(blocksOf(paper)).allSatisfy(b -> assertThat(b.getContent().has("textKor")).isFalse());
    }

    @Test
    @DisplayName("completed인데 manifest에 사이드카가 없으면 병합 없이 COMPLETED")
    void completedWithoutSidecarStillCompletes() {
        Paper paper = givenProcessingPaper("no-sidecar.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenPackageOnS3(paper.getId());   // 사이드카 항목 없음, 언어 null
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);
        documentTransitions.markCompileRequested(paper.getDocumentId());

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
        assertThat(blocksOf(paper)).allSatisfy(b -> assertThat(b.getContent().has("textKor")).isFalse());
        Document document = documentOf(paper);
        assertThat(document.getCompileStatus()).isEqualTo(CompileStatus.COMPLETED);
        assertThat(document.getKnowledgeGraphKey()).isNull();
        assertThat(document.knowledgeGraphStatus()).isEqualTo(KnowledgeGraphStatus.FAILED);
    }

    @Test
    @DisplayName("중복 전달: 이미 COMPLETED이면 아무것도 바꾸지 않고 소비한다")
    void duplicateResultIsConsumed() {
        Paper paper = givenRequestedPaper("compile-dup.pdf");
        String message = """
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKeyOf(paper));

        publishCompileResult(message);
        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
        publishCompileResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PAPER_ID_INVALID","message":"late"}}
                """.formatted(paper.getId()));
        awaitConsumed(compileResultQueueUrl());

        assertThat(documentOf(paper).getCompileStatus()).isEqualTo(CompileStatus.COMPLETED);
        assertThat(documentOf(paper).getCompileErrorCode()).isNull();
        assertThat(documentOf(paper).getKnowledgeGraphKey())
                .isEqualTo("papers/" + paper.getId() + "/knowledge-bundle/viz.html");
    }

    @Test
    @DisplayName("선점 커밋 전에 도착한 결과(compile_status null)도 반영한다")
    void resultBeforeRequestedIsApplied() {
        Paper paper = givenProcessingPaper("early.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKeyOf(paper)));

        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
    }

    @Test
    @DisplayName("알 수 없는 paper_id: 상태 변경 없이 소비한다")
    void unknownPaperIdIsConsumed() {
        UUID unknown = UUID.randomUUID();

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"papers/%s/manifest.json"}
                """.formatted(unknown, unknown));

        awaitConsumed(compileResultQueueUrl());
        assertThat(documentRepository.count()).isZero();
    }

    @ParameterizedTest(name = "비복구 입력이라 소비만 한다: {0}")
    @ValueSource(strings = {
            "{\"paper_id\": \"%s\", \"status\": \"running\"}",
            "{\"paper_id\": \"%s\", \"status\": \"completed\"}",       // manifest_key 누락
            "{\"paper_id\": \"%s\", \"status\": \"failed\"}",          // error.code 누락
            "{\"status\": \"completed\", \"manifest_key\": \"k\"}",   // paper_id 누락
            "{ this is not json",
    })
    void nonRecoverableMessagesAreConsumed(String template) {
        Paper paper = givenRequestedPaper("non-recoverable.pdf");

        publishCompileResult(template.contains("%s") ? template.formatted(paper.getId()) : template);

        awaitConsumed(compileResultQueueUrl());
        assertThat(documentOf(paper).getCompileStatus()).isEqualTo(CompileStatus.REQUESTED);
    }
}
