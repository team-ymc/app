package com.ymc.paper.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.FailedPaperIntegrationTest;

class PaperRetryFieldsIntegrationTest extends FailedPaperIntegrationTest {

    private Paper givenRefundedPaper() {
        Paper a = givenReservedPaper(TEST_USER_ID, "a.pdf");
        Paper b = givenReservedPaper(OTHER_USER_ID, "b.pdf");
        givenSharedFailedDocument(a, b);
        return reload(a.getId());
    }

    private Paper givenCompletedPaper(CompileStatus compileStatus) {
        Paper paper = givenProcessingPaper("completed.pdf");
        documentTransitions.markParsedAndSettle(
                paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentTransitions.markCompileRequested(paper.getDocumentId());
        documentTransitions.markCompiled(paper.getDocumentId(), compileStatus,
                compileStatus == CompileStatus.FAILED ? "LLM_RATE_LIMITED" : null, null);
        return reload(paper.getId());
    }

    @Test
    @DisplayName("실패한 논문은 재시도할 수 있는 실패로 표시된다")
    void failedPaperIsRetryable() throws Exception {
        Paper paper = givenRefundedPaper();

        mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failReason").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.compileRetryable").value(false));
        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(jsonPath("$.papers[0].failReason").value("PROCESSING_FAILED"));
    }

    @Test
    @DisplayName("시도 횟수를 모두 쓴 파일은 재시도할 수 없는 실패로 표시된다")
    void exhaustedPaperIsBlocked() throws Exception {
        Paper paper = givenRefundedPaper();
        jdbcTemplate.update("update document set attempt = ? where id = ?",
                Document.MAX_ATTEMPTS, paper.getDocumentId());

        mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.failReason").value("RETRY_LIMIT_EXCEEDED"));
        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(jsonPath("$.papers[0].failReason").value("RETRY_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("횟수를 모두 썼어도 Document가 이미 완료됐으면 재시도할 수 있다")
    void exhaustedButCompletedIsRetryable() throws Exception {
        Paper paper = givenRefundedPaper();
        jdbcTemplate.update("update document set attempt = ?, status = 'COMPLETED' where id = ?",
                Document.MAX_ATTEMPTS, paper.getDocumentId());

        mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failReason").value("PROCESSING_FAILED"));
    }

    @Test
    @DisplayName("실패가 아닌 논문의 실패 사유는 null이다")
    void nonFailedHasNoReason() throws Exception {
        Paper paper = givenCompletedPaper(CompileStatus.COMPLETED);

        mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.failReason").value(nullValue()))
                .andExpect(jsonPath("$.compileRetryable").value(false));
        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(jsonPath("$.papers[0].failReason").value(nullValue()));
    }

    @Test
    @DisplayName("컴파일만 실패했고 횟수가 남았으면 컴파일을 재시도할 수 있다")
    void compileFailedIsRetryable() throws Exception {
        Paper paper = givenCompletedPaper(CompileStatus.FAILED);

        mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.compileRetryable").value(true));
    }

    @Test
    @DisplayName("컴파일 시도 횟수를 모두 썼으면 컴파일을 재시도할 수 없다")
    void compileExhaustedIsNotRetryable() throws Exception {
        Paper paper = givenCompletedPaper(CompileStatus.FAILED);
        jdbcTemplate.update("update document set compile_attempt = ? where id = ?",
                Document.MAX_ATTEMPTS, paper.getDocumentId());

        mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.compileRetryable").value(false));
    }

    @Test
    @DisplayName("업로드가 만료된 논문은 실패 사유가 없다")
    void expiredHasNoReason() throws Exception {
        Paper pending = givenPendingPaper("expired.pdf");
        jdbcTemplate.update("update paper set expired_at = now() where id = ?", pending.getId());

        mockMvc.perform(get("/api/papers/{id}/status", pending.getId()).with(userJwt()))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.failReason").value(nullValue()))
                .andExpect(jsonPath("$.compileRetryable").value(false));
    }
}
