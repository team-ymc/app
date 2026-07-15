package com.ymc.paper.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

/** spec: paper-list (Task 3B). */
class PaperListIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("빈 서재: 200과 papers: []")
    void returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/papers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers").isArray())
                .andExpect(jsonPath("$.papers").isEmpty());
    }

    @Test
    @DisplayName("등록된 논문: paperId·filename·status·createdAt·updatedAt 행 반환")
    void returnsRegisteredPapers() throws Exception {
        Paper paper = givenProcessingPaper("attention.pdf");

        mockMvc.perform(get("/api/papers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(1))
                .andExpect(jsonPath("$.papers[0].paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.papers[0].filename").value("attention.pdf"))
                .andExpect(jsonPath("$.papers[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.papers[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.papers[0].updatedAt").isNotEmpty());
    }
}
