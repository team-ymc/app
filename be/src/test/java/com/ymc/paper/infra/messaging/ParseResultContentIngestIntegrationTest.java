package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.PaperContentIngestService;
import com.ymc.support.IntegrationTest;

/** messaging.yml 0.2.0 wire 형식 기준. */
class ParseResultContentIngestIntegrationTest extends IntegrationTest {

    @Autowired
    PaperContentIngestService ingestService;

    @Test
    void completed_메시지는_전이와_적재까지_수행한다() {
        Paper paper = givenProcessingPaper("with-manifest.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));
        awaitConsumed(parseResultQueueUrl());

        await().atMost(CONSUME_TIMEOUT).untilAsserted(() -> {
            assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.COMPLETED);
            assertThat(ingestService.isIngested(paper.getId())).isTrue();
        });
    }

    @Test
    void manifest_key_없는_completed는_계약_위반으로_폐기된다() {
        Paper paper = givenProcessingPaper("no-manifest.pdf");

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok"}
                """.formatted(paper.getId()));
        awaitConsumed(parseResultQueueUrl());

        // 폐기 = 정상 소비(ack)하되 아무것도 반영하지 않는다
        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.PROCESSING);
        assertThat(ingestService.isIngested(paper.getId())).isFalse();
    }

    @Test
    void 이미_COMPLETED인_논문에_재전달돼도_미적재면_적재한다() {
        // 시나리오: 전이는 커밋됐는데 적재가 실패해 메시지가 재전달된 경우
        Paper paper = givenProcessingPaper("redelivery.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));
        awaitConsumed(parseResultQueueUrl());

        await().atMost(CONSUME_TIMEOUT).untilAsserted(
                () -> assertThat(ingestService.isIngested(paper.getId())).isTrue());
    }

    @Test
    void failed_메시지는_전이만_하고_적재하지_않는다() {
        Paper paper = givenProcessingPaper("failed.pdf");

        publishParseResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSE_RETRIES_EXHAUSTED","message":"재시도 소진"}}
                """.formatted(paper.getId()));
        awaitConsumed(parseResultQueueUrl());

        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.FAILED);
        assertThat(reload(paper.getId()).getErrorCode()).isEqualTo("PARSE_RETRIES_EXHAUSTED");
        assertThat(ingestService.isIngested(paper.getId())).isFalse();
    }
}
