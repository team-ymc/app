package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.ymc.paper.service.port.PrerequisiteGenerationLock;
import com.ymc.support.FakeAiJsonServer;
import com.ymc.support.IntegrationTest;

/** Valkey 장애로 잠금을 못 얻는 경우만 다룬다. 다른 케이스는 {@link PrerequisiteDefinitionIntegrationTest}가 실물로 검증한다. */
class PrerequisiteDefinitionLockOutageIntegrationTest extends IntegrationTest {

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
    PrerequisiteGenerationLock lock;

    @BeforeEach
    void resetFakes() {
        aiServer.reset();
    }

    private Paper givenCompiledPaper() {
        Paper paper = givenProcessingPaper("def-outage.pdf");
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
    void Valkey_장애로_잠금을_못_얻으면_AI를_부르지_않고_502다() throws Exception {
        Paper paper = givenCompiledPaper();
        when(lock.tryAcquire(any())).thenThrow(
                new PrerequisiteGenerationLock.LockUnavailableException("down", null));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_DEFINITION_FAILED"));
        assertThat(aiServer.calls()).isZero();
    }
}
