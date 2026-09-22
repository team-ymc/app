package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.DocumentPrerequisiteHighlightIngestService;
import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;
import com.ymc.paper.service.port.PrerequisiteGenerationLock;
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

    @MockitoBean
    PrerequisiteDefinitionCache cache;

    @MockitoBean
    PrerequisiteGenerationLock lock;

    @BeforeEach
    void resetFakes() {
        aiServer.reset();
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(lock.tryAcquire(any())).thenReturn(Optional.of("token"));
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
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("new architecture", "An en line", "국문 한 줄", "0.1")));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.term").value("new architecture"))
                .andExpect(jsonPath("$.definitionEn").value("An en line"))
                .andExpect(jsonPath("$.definitionKo").value("국문 한 줄"));

        assertThat(aiServer.calls()).isEqualTo(1);
        assertThat(aiServer.lastRequestBody()).contains("\"paper_id\":\"" + paper.getId() + "\"")
                .contains("\"block_id\":\"p0000-b0001\"").contains("\"offset\":13").contains("\"offset\":29");
        verify(cache).put(anyString(), any(PrerequisiteDefinition.class));
        verify(lock).release(any(), any());
        assertThat(usageRecordRepository.findAll()).isEmpty();
    }

    @Test
    void HIT면_AI를_부르지_않고_잠금도_잡지_않는다() throws Exception {
        Paper paper = givenCompiledPaper();
        when(cache.get(anyString())).thenReturn(Optional.of(new PrerequisiteDefinition("cached en", "캐시 국문")));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionEn").value("cached en"));

        assertThat(aiServer.calls()).isZero();
        verify(lock, never()).tryAcquire(any());
    }

    @Test
    void 잠금을_못_잡으면_429다() throws Exception {
        Paper paper = givenCompiledPaper();
        when(lock.tryAcquire(any())).thenReturn(Optional.empty());

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

        verify(cache, never()).put(anyString(), any());
        verify(lock).release(any(), any());
    }

    @Test
    void Valkey_장애로_잠금을_못_얻으면_AI를_부르지_않고_502다() throws Exception {
        Paper paper = givenCompiledPaper();
        when(lock.tryAcquire(any())).thenThrow(
                new PrerequisiteGenerationLock.LockUnavailableException("down", null));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_DEFINITION_FAILED"));
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
