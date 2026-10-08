package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.DocumentPrerequisiteHighlightIngestService;
import com.ymc.support.FakeAiJsonServer;
import com.ymc.support.FakeAiJsonServer.Reply;
import com.ymc.support.IntegrationTest;

/** 체험 경로(/api/trial/papers). 인증 없이 paper.trial인 논문만 읽힌다. */
class TrialPaperIntegrationTest extends IntegrationTest {

    private static final String VIZ_SUFFIX = "/knowledge-bundle/viz.html";

    static FakeAiJsonServer aiServer = new FakeAiJsonServer();

    @BeforeAll
    static void startAi() {
        aiServer.start();
    }

    @AfterAll
    static void stopAi() {
        aiServer.close();
    }

    @DynamicPropertySource
    static void aiBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("ai.base-url", () -> aiServer.baseUrl());
    }

    @Autowired
    DocumentPrerequisiteHighlightIngestService highlightIngestService;

    @BeforeEach
    void resetFakes() {
        aiServer.reset();
    }

    /** 파싱·적재·컴파일까지 끝난 논문. 체험 여부는 각 테스트가 정한다. */
    private Paper givenCompiledPaper(String filename) {
        Paper paper = givenProcessingPaper(filename);
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);
        highlightIngestService.ingest(paper.getDocumentId(), manifestKey);
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null,
                "papers/" + paper.getId() + VIZ_SUFFIX);
        return reload(paper.getId());
    }

    /** 운영자가 SQL로 켜는 것과 같은 경로. */
    private void markTrial(Paper paper) {
        jdbcTemplate.update("update paper set trial = true where id = ?", paper.getId());
    }

    @Test
    @DisplayName("체험 논문 본문: 인증 없이 200, 선행지식 포함, 조회 시각은 남기지 않음")
    void servesTrialContentWithoutAuth() throws Exception {
        Paper paper = givenCompiledPaper("trial-content.pdf");
        markTrial(paper);

        mockMvc.perform(get("/api/trial/papers/{id}/content", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.blocks").isNotEmpty())
                .andExpect(jsonPath("$.prerequisiteHighlights").isNotEmpty());

        assertThat(reload(paper.getId()).getLastAccessedAt()).isNull();
    }

    @Test
    @DisplayName("체험이 아닌 논문: 인증 없이 404 PAPER_NOT_FOUND로 존재를 숨김")
    void hidesNonTrialPaper() throws Exception {
        Paper paper = givenCompiledPaper("not-trial.pdf");

        mockMvc.perform(get("/api/trial/papers/{id}/content", paper.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
        mockMvc.perform(get("/api/trial/papers/{id}/knowledge-graph", paper.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
        mockMvc.perform(post("/api/trial/papers/{id}/prerequisite-highlights/{h}/definition",
                        paper.getId(), "prerequisite-0001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 paperId: 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(get("/api/trial/papers/{id}/content", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("체험 논문 지식 그래프: 인증 없이 presigned URL")
    void servesTrialKnowledgeGraph() throws Exception {
        Paper paper = givenCompiledPaper("trial-graph.pdf");
        markTrial(paper);

        mockMvc.perform(get("/api/trial/papers/{id}/knowledge-graph", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString(paper.getId() + VIZ_SUFFIX)));
    }

    @Test
    @DisplayName("체험 논문 선행지식 설명: 미리 만든 것만 주고, 없으면 AI를 부르지 않고 409")
    void servesOnlyPrebuiltTrialDefinitions() throws Exception {
        Paper paper = givenCompiledPaper("trial-def.pdf");
        markTrial(paper);

        mockMvc.perform(post("/api/trial/papers/{id}/prerequisite-highlights/{h}/definition",
                        paper.getId(), "prerequisite-0001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_NOT_READY"));
        assertThat(aiServer.calls()).isZero();

        // 운영자가 로그인 경로로 만들어 두면 체험 경로가 HIT하고, 설명은 만료 없이 남는다.
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("new architecture", "An en line", "국문 한 줄", "0.1")));
        mockMvc.perform(post("/api/papers/{id}/prerequisite-highlights/{h}/definition",
                        paper.getId(), "prerequisite-0001").with(userJwt()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/trial/papers/{id}/prerequisite-highlights/{h}/definition",
                        paper.getId(), "prerequisite-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.term").value("new architecture"))
                .andExpect(jsonPath("$.definitionKo").value("국문 한 줄"));
        assertThat(aiServer.calls()).isEqualTo(1);

        Set<String> keys = redisTemplate.keys("prerequisite-definition:v1:" + paper.getDocumentId() + ":*");
        assertThat(keys).hasSize(1);
        assertThat(redisTemplate.getExpire(keys.iterator().next())).isEqualTo(-1L);
    }

    @Test
    @DisplayName("만료되거나 깨진 bearer 헤더가 있어도 체험 경로는 200 (계약 security: [])")
    void ignoresStaleBearerToken() throws Exception {
        Paper paper = givenCompiledPaper("trial-stale-token.pdf");
        markTrial(paper);

        mockMvc.perform(get("/api/trial/papers/{id}/content", paper.getId())
                        .header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(paper.getId().toString()));
    }

    @Test
    @DisplayName("기존 /api/papers 경로는 여전히 인증이 필요하다")
    void ownerPathStillRequiresAuth() throws Exception {
        Paper paper = givenCompiledPaper("still-auth.pdf");
        markTrial(paper);

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()))
                .andExpect(status().isUnauthorized());
    }
}
