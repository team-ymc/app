package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.TranslationStatus;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.services.sqs.model.Message;

/** 파싱 완료 결과 → 본문 적재 → knowledge-compile-requests 발행. */
class KnowledgeCompileStartIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("파싱 완료·적재 뒤 같은 paper_id와 manifest_key로 컴파일을 요청하고 REQUESTED가 된다")
    void publishesCompileRequestAfterIngest() throws Exception {
        Paper paper = givenProcessingPaper("compile.pdf");
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitCompileStatus(paper.getId(), CompileStatus.REQUESTED);
        List<Message> requests = receive(compileRequestQueueUrl(), 5);
        assertThat(requests).hasSize(1);
        JsonNode body = objectMapper.readTree(requests.get(0).body());
        assertThat(body.get("paper_id").asText()).isEqualTo(paper.getId().toString());
        assertThat(body.get("manifest_key").asText()).isEqualTo(manifestKey);
        assertThat(documentOf(paper).translationStatus()).isEqualTo(TranslationStatus.PENDING);
    }

    @Test
    @DisplayName("한국어 논문도 컴파일은 요청하지만 번역 상태는 NOT_APPLICABLE이다")
    void requestsCompileForNonEnglishToo() {
        Paper paper = givenProcessingPaper("korean.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());   // source_language 없음 → null

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitCompileStatus(paper.getId(), CompileStatus.REQUESTED);
        assertThat(receive(compileRequestQueueUrl(), 5)).hasSize(1);
        assertThat(documentOf(paper).translationStatus()).isEqualTo(TranslationStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("파싱 결과가 중복 전달돼도 컴파일 요청은 한 번만 발행된다")
    void duplicateParseResultPublishesOnce() {
        Paper paper = givenProcessingPaper("dup.pdf");
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());
        String message = """
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey);

        publishParseResult(message);
        awaitCompileStatus(paper.getId(), CompileStatus.REQUESTED);
        publishParseResult(message);
        awaitConsumed(parseResultQueueUrl());

        assertThat(receive(compileRequestQueueUrl(), 2)).hasSize(1);
        verify(knowledgeCompileRequestPublisher, times(1)).publish(eq(paper.getId()), any());
    }

    @Test
    @DisplayName("발행 실패: REQUESTED를 반납해 PENDING을 유지하고, 재전달에서 다시 발행한다")
    void publishFailureRevertsAndRetriesOnRedelivery() {
        Paper paper = givenProcessingPaper("publish-fail.pdf");
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());
        doThrow(new IllegalStateException("SQS 장애"))
                .doCallRealMethod()
                .when(knowledgeCompileRequestPublisher).publish(eq(paper.getId()), any());

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        // 첫 시도 실패 직후: 본문은 적재됐고 컴파일 상태는 null(=PENDING)
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            verify(knowledgeCompileRequestPublisher).publish(eq(paper.getId()), any());
            assertThat(documentContentIngestService.isIngested(paper.getDocumentId())).isTrue();
        });
        assertThat(documentOf(paper).translationStatus()).isEqualTo(TranslationStatus.PENDING);

        // 재전달(visibility 2초) 뒤 성공
        awaitCompileStatus(paper.getId(), CompileStatus.REQUESTED);
        assertThat(receive(compileRequestQueueUrl(), 5)).hasSize(1);
        verify(knowledgeCompileRequestPublisher, times(2)).publish(eq(paper.getId()), any());
    }

    @Test
    @DisplayName("파싱 실패 결과에는 컴파일을 요청하지 않는다")
    void failedParseDoesNotRequestCompile() {
        Paper paper = givenProcessingPaper("parse-failed.pdf");

        publishParseResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSE_RETRIES_EXHAUSTED","message":"x"}}
                """.formatted(paper.getId()));
        awaitConsumed(parseResultQueueUrl());

        assertThat(documentOf(paper).getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(documentOf(paper).getCompileStatus()).isNull();
        assertThat(receive(compileRequestQueueUrl(), 1)).isEmpty();
    }

    private Document documentOf(Paper paper) {
        return documentRepository.findByRequestPaperId(paper.getId()).orElseThrow();
    }

    private void awaitCompileStatus(UUID requestPaperId, CompileStatus expected) {
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(documentRepository.findByRequestPaperId(requestPaperId)
                        .orElseThrow().getCompileStatus()).isEqualTo(expected));
    }
}
