package com.ymc.paper.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

/** 다른 사용자의 재시도로 Document는 완료됐지만 이 Paper는 환불된 채 남은 상태의 접근. */
class FailedPaperAccessIntegrationTest extends IntegrationTest {

    /** 파싱·적재·컴파일이 모두 끝난 논문. */
    private Paper givenFullyReadyPaper(String filename) {
        Paper paper = givenProcessingPaper(filename);
        documentTransitions.markParsedAndSettle(
                paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(
                paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null,
                "papers/" + paper.getId() + "/knowledge-bundle/viz.html");
        return reload(paper.getId());
    }

    private Paper givenRefundedPaperOnReadyDocument() {
        Paper paper = givenFullyReadyPaper("refunded.pdf");
        jdbcTemplate.update(
                "update paper set failed_at = now(), failed_error_code = 'PARSE_FAILED' where id = ?",
                paper.getId());
        return reload(paper.getId());
    }

    @Test
    @DisplayName("환불 표시가 없으면 본문과 지식 그래프가 열린다")
    void readyPaperOpens() throws Exception {
        Paper paper = givenFullyReadyPaper("ready.pdf");

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("환불된 Paper는 상태와 목록에서 FAILED로 보인다")
    void refundedPaperShowsFailed() throws Exception {
        Paper paper = givenRefundedPaperOnReadyDocument();

        mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers[0].status").value("FAILED"));
    }

    @Test
    @DisplayName("환불된 Paper는 본문을 열 수 없다")
    void refundedPaperCannotReadContent() throws Exception {
        Paper paper = givenRefundedPaperOnReadyDocument();

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_READY"));
    }

    @Test
    @DisplayName("환불된 Paper는 지식 그래프를 열 수 없다")
    void refundedPaperCannotOpenGraph() throws Exception {
        Paper paper = givenRefundedPaperOnReadyDocument();

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("환불된 Paper는 선행지식 설명을 받을 수 없다")
    void refundedPaperCannotDefinePrerequisite() throws Exception {
        Paper paper = givenRefundedPaperOnReadyDocument();

        mockMvc.perform(post("/api/papers/{id}/prerequisite-highlights/{highlightId}/definition",
                        paper.getId(), "any-highlight").with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_NOT_READY"));
    }
}
