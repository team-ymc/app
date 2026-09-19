package com.ymc.paper.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

/** spec: paper-list (Task 3B). */
class PaperListIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("빈 서재: 200과 papers: []")
    void returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers").isArray())
                .andExpect(jsonPath("$.papers").isEmpty());
    }

    @Test
    @DisplayName("파싱 전 논문: title은 filename으로 폴백한다")
    void returnsRegisteredPapers() throws Exception {
        Paper paper = givenProcessingPaper("attention.pdf");

        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(1))
                .andExpect(jsonPath("$.papers[0].paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.papers[0].title").value("attention.pdf"))
                .andExpect(jsonPath("$.papers[0].filename").value("attention.pdf"))
                .andExpect(jsonPath("$.papers[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.papers[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.papers[0].updatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("파싱 후 논문: document_content의 AI 제목을 내려준다")
    void returnsParsedTitle() throws Exception {
        Paper paper = givenProcessingPaper("uploaded-name.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);

        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers[0].title").value("Fixture Paper Title"))
                .andExpect(jsonPath("$.papers[0].filename").value("uploaded-name.pdf"));

        tx.executeWithoutResult(s -> reload(paper.getId()).renameTitle("My title"));
        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers[0].title").value("My title"))
                .andExpect(jsonPath("$.papers[0].filename").value("uploaded-name.pdf"));
    }

    @Test
    @DisplayName("lastAccessedAt: 접근 전에는 null 키로 내려가고, 접근 후에는 시각이 내려간다")
    void returnsLastAccessedAt() throws Exception {
        Paper untouched = givenPendingPaper("untouched.pdf");
        Paper accessed = givenPendingPaper("accessed.pdf");
        tx.executeWithoutResult(s -> reload(accessed.getId()).markAccessed(Instant.now()));

        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers[?(@.filename=='accessed.pdf')].lastAccessedAt")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.notNullValue())))
                .andExpect(jsonPath("$.papers[?(@.filename=='untouched.pdf')].lastAccessedAt")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    @DisplayName("다른 사용자의 논문은 목록에 나오지 않는다 — 소유자 필터링 (YMC-215)")
    void 다른_사용자_논문은_안_보인다() throws Exception {
        givenPendingPaper("mine.pdf");
        paperRepository.save(Paper.register(UUID.randomUUID(), "others.pdf", Instant.now()));

        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(1))
                .andExpect(jsonPath("$.papers[0].filename").value("mine.pdf"));
    }
}
