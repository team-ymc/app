package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

/** spec: paper-status-polling (tasks 5.2). */
class PaperStatusPollingIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("진행 중 상태 조회: 200과 {paperId, status, updatedAt}")
    void returnsCurrentStatus() throws Exception {
        Paper paper = givenProcessingPaper("processing.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                // MVP는 실패 코드를 사용자에게 노출하지 않는다 — 상태값만 나간다
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @ParameterizedTest(name = "{0} 상태를 그대로 응답한다")
    @EnumSource(value = PaperStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("terminal 상태 조회")
    void returnsTerminalStatus(PaperStatus terminal) throws Exception {
        Paper paper = givenProcessingPaper("terminal-" + terminal + ".pdf");
        String errorCode = terminal == PaperStatus.FAILED ? "PDF_UNREADABLE" : null;
        paperTransitions.markParsed(paper.getId(), terminal, errorCode);

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(terminal.name()));
    }

    @Test
    @DisplayName("상태 전이 반영: updatedAt이 전이 시각으로 갱신된다")
    void updatedAtTracksTransition() throws Exception {
        Paper paper = givenProcessingPaper("transition.pdf");
        Instant beforeTransition = reload(paper.getId()).getUpdatedAt();

        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);

        JsonNode body = objectMapper.readTree(
                mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPLETED"))
                        .andReturn().getResponse().getContentAsString());

        Instant afterTransition = Instant.parse(body.get("updatedAt").asText());
        assertThat(afterTransition).isAfterOrEqualTo(beforeTransition);
        assertThat(afterTransition).isEqualTo(reload(paper.getId()).getUpdatedAt());
    }

    @Test
    @DisplayName("없는 paperId: 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{paperId}/status", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("UUID가 아닌 paperId: 400 VALIDATION_ERROR")
    void rejectsMalformedPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{paperId}/status", "not-a-uuid").with(userJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
