package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

class PrerequisiteDefinitionIntegrationTest extends IntegrationTest {

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

    private Paper givenCompiledPaper() {
        Paper paper = givenProcessingPaper("def.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);
        highlightIngestService.ingest(paper.getDocumentId(), manifestKey);
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null, null);
        return reload(paper.getId());
    }

    private static String url(Paper paper, String highlightId) {
        return "/api/papers/" + paper.getId() + "/prerequisite-highlights/" + highlightId + "/definition";
    }

    @Test
    void MISS면_AI를_불러_설명을_만들고_캐시에_넣는다() throws Exception {
        Paper paper = givenCompiledPaper();
        // 같은 Document를 가리키는 두 번째 Paper(중복 업로드 연결 경로) — AI 요청의 paper_id가
        // 요청자의 paperId가 아니라 Document.requestPaperId(첫 Paper)여야 함을 구분해 검증한다.
        Paper second = paperRepository.save(Paper.register(TEST_USER_ID, "dup.pdf", Instant.now()));
        tx.execute(s -> paperRepository.linkDocument(second.getId(), paper.getDocumentId(), Instant.now()));
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("new architecture", "An en line", "국문 한 줄", "0.1")));

        mockMvc.perform(post(url(reload(second.getId()), "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.term").value("new architecture"))
                .andExpect(jsonPath("$.definitionEn").value("An en line"))
                .andExpect(jsonPath("$.definitionKo").value("국문 한 줄"));

        assertThat(aiServer.calls()).isEqualTo(1);
        assertThat(aiServer.lastRequestBody()).contains("\"paper_id\":\"" + paper.getId() + "\"")
                .doesNotContain("\"paper_id\":\"" + second.getId() + "\"")
                .contains("\"block_id\":\"p0000-b0001\"").contains("\"offset\":13").contains("\"offset\":29");
        assertThat(redisTemplate.keys("prerequisite-definition:v1:*")).hasSize(1);
        assertThat(redisTemplate.hasKey("prerequisite-definition:lock:" + TEST_USER_ID)).isFalse();
        assertThat(usageRecordRepository.findAll()).isEmpty();
    }

    @Test
    void HIT면_AI를_부르지_않고_잠금도_잡지_않는다() throws Exception {
        Paper paper = givenCompiledPaper();
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("new architecture", "cached en", "캐시 국문", "0.1")));
        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt())).andExpect(status().isOk());
        aiServer.reset();

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionEn").value("cached en"));

        assertThat(aiServer.calls()).isZero();
    }

    @Test
    void 잠금을_못_잡으면_429다() throws Exception {
        Paper paper = givenCompiledPaper();
        redisTemplate.opsForValue().set(
                "prerequisite-definition:lock:" + TEST_USER_ID, "busy", Duration.ofSeconds(30));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED"));
        assertThat(aiServer.calls()).isZero();
    }

    @Test
    void AI_실패는_502이고_캐시에_넣지_않는다() throws Exception {
        Paper paper = givenCompiledPaper();
        aiServer.enqueue(Reply.error(502, "{\"detail\":{\"code\":\"DEFINITION_OUTPUT_INVALID\",\"message\":\"x\","
                + "\"estimated_cost_usd\":\"0.1\"}}"));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_DEFINITION_FAILED"));

        assertThat(redisTemplate.keys("prerequisite-definition:v1:*")).isEmpty();
        assertThat(redisTemplate.hasKey("prerequisite-definition:lock:" + TEST_USER_ID)).isFalse();
    }

    @Test
    void 같은_사용자가_같은_하이라이트를_두_번_요청하면_두_번째는_AI를_부르지_않는다() throws Exception {
        Paper paper = givenCompiledPaper();
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("new architecture", "en", "ko", "0.1")));
        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt())).andExpect(status().isOk());
        aiServer.reset();

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionEn").value("en"));
        assertThat(aiServer.calls()).isZero();
    }

    @Test
    void 없는_highlightId는_404다() throws Exception {
        Paper paper = givenCompiledPaper();

        mockMvc.perform(post(url(paper, "prerequisite-9999")).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_HIGHLIGHT_NOT_FOUND"));
    }

    @Test
    void 컴파일_전이면_409다() throws Exception {
        Paper paper = givenProcessingPaper("notready.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));

        mockMvc.perform(post(url(reload(paper.getId()), "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_NOT_READY"));
    }

    @Test
    void 남의_논문은_403이다() throws Exception {
        Paper paper = givenCompiledPaper();

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(otherUserJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_논문은_404다() throws Exception {
        mockMvc.perform(post("/api/papers/" + UUID.randomUUID() + "/prerequisite-highlights/h/definition")
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }
}
