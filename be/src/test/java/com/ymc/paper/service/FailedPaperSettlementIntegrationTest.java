package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.support.FailedPaperIntegrationTest;

class FailedPaperSettlementIntegrationTest extends FailedPaperIntegrationTest {

    /** A만 다시 신청해 파싱이 다시 도는 상태를 직접 만든다. 재시도 API는 뒤 태스크에서 생긴다. */
    private void givenOnlyFirstRetried(Paper first, Document document) {
        jdbcTemplate.update(
                "update paper set failed_at = null, failed_error_code = null where id = ?", first.getId());
        jdbcTemplate.update(
                "update usage_record set status = 'RESERVED' where source_id = ?", first.getId());
        jdbcTemplate.update("update document set status = 'PROCESSING' where id = ?", document.getId());
    }

    @Test
    @DisplayName("실패 정산은 연결된 Paper를 모두 환불하고 실패로 표시한다")
    void failureMarksAllLinkedPapers() {
        Paper a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        Paper b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        Document document = givenSharedProcessingDocument(a, b);

        documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.FAILED, "PARSE_FAILED");

        for (Paper paper : List.of(a, b)) {
            Paper reloaded = reload(paper.getId());
            assertThat(reloaded.getFailedAt()).isNotNull();
            assertThat(reloaded.getFailedErrorCode()).isEqualTo("PARSE_FAILED");
            assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        }
    }

    @Test
    @DisplayName("성공 정산은 실패 표시가 남은 Paper를 확정하지 않는다")
    void successSkipsFailedPapers() {
        Paper a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        Paper b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        Document document = givenSharedFailedDocument(a, b);
        givenOnlyFirstRetried(a, document);
        // B를 RESERVED로 되돌려 둔다 — failed_at 필터가 없으면 확정되어 이 테스트가 실패한다
        jdbcTemplate.update("update usage_record set status = 'RESERVED' where source_id = ?", b.getId());

        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);

        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(reload(a.getId()).getFailedAt()).isNull();
        assertThat(recordStatusOf(b.getId())).isEqualTo(UsageRecordStatus.RESERVED);
        assertThat(reload(b.getId()).getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("두 번째 실패 정산은 이미 표시된 Paper의 실패 코드를 덮어쓰지 않는다")
    void secondFailureKeepsEarlierMark() {
        Paper a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        Paper b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        Document document = givenSharedFailedDocument(a, b);
        givenOnlyFirstRetried(a, document);

        documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.FAILED, "SECOND_FAILURE");

        assertThat(reload(a.getId()).getFailedErrorCode()).isEqualTo("SECOND_FAILURE");
        assertThat(recordStatusOf(a.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(reload(b.getId()).getFailedErrorCode()).isEqualTo("PARSE_FAILED");
    }
}
