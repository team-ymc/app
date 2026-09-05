package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.PaperAccessRecorder;
import com.ymc.support.IntegrationTest;

/** 논리 삭제된 논문은 사용자 경로 어디서도 보이지 않는다. */
class DeletedPaperVisibilityIntegrationTest extends IntegrationTest {

    @Autowired
    PaperAccessRecorder accessRecorder;

    private Paper givenDeletedProcessingPaper(String filename) {
        Paper paper = givenProcessingPaper(filename);
        tx.executeWithoutResult(s -> paperRepository.markDeleted(paper.getId(), Instant.now()));
        return reload(paper.getId());
    }

    @Test
    @DisplayName("목록에서 빠진다")
    void excludedFromList() throws Exception {
        givenDeletedProcessingPaper("gone.pdf");
        givenProcessingPaper("alive.pdf");

        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(1))
                .andExpect(jsonPath("$.papers[0].filename").value("alive.pdf"));
    }

    @Test
    @DisplayName("status·content·download·chat sessions·complete 전부 404 PAPER_NOT_FOUND")
    void userPathsReturnNotFound() throws Exception {
        Paper paper = givenDeletedProcessingPaper("gone.pdf");

        for (String path : new String[] {"/status", "/content", "/download", "/chat/sessions"}) {
            mockMvc.perform(get("/api/papers/{id}" + path, paper.getId()).with(userJwt()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
        }
        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("접근 기록은 삭제 행을 갱신하지 않는다")
    void accessRecorderSkipsDeleted() {
        Paper paper = givenDeletedProcessingPaper("gone.pdf");

        accessRecorder.recordAccess(paper.getId(), Instant.now());

        assertThat(reload(paper.getId()).getLastAccessedAt()).isNull();
    }
}
