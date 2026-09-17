package com.ymc.paper.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class KnowledgeGraphViewIntegrationTest extends IntegrationTest {

    private static final String VIZ_SUFFIX = "/knowledge-bundle/viz.html";

    /** 파싱·적재까지 끝난 논문. 컴파일 상태는 각 테스트가 정한다. */
    private Paper givenIngestedPaper(String filename) {
        Paper paper = givenProcessingPaper(filename);
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        return reload(paper.getId());
    }

    @Test
    @DisplayName("READY: 200과 viz.html을 가리키는 presigned URL, Content-Disposition 없음")
    void returnsPresignedUrlWhenReady() throws Exception {
        Paper paper = givenIngestedPaper("graph-ready.pdf");
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null,
                "papers/" + paper.getId() + VIZ_SUFFIX);

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString(paper.getId() + VIZ_SUFFIX)))
                .andExpect(jsonPath("$.url").value(not(containsString("response-content-disposition"))))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("PENDING(REQUESTED): 409 KNOWLEDGE_GRAPH_NOT_READY")
    void rejectsWhilePending() throws Exception {
        Paper paper = givenIngestedPaper("graph-pending.pdf");
        documentTransitions.markCompileRequested(paper.getDocumentId());

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("FAILED: 409 KNOWLEDGE_GRAPH_NOT_READY")
    void rejectsWhenFailed() throws Exception {
        Paper paper = givenIngestedPaper("graph-failed.pdf");
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.FAILED, "PARSED_DOCUMENT_INVALID", null);

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("COMPLETED인데 키 없음: 409 KNOWLEDGE_GRAPH_NOT_READY")
    void rejectsWhenCompletedWithoutKey() throws Exception {
        Paper paper = givenIngestedPaper("graph-no-key.pdf");
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null, null);

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("업로드 전(Document 미연결): 409 KNOWLEDGE_GRAPH_NOT_READY")
    void rejectsPendingUpload() throws Exception {
        Paper paper = givenPendingPaper("graph-upload-pending.pdf");

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("없는 paperId: 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("남의 논문: presigned URL을 발급하지 않고 403 FORBIDDEN")
    void rejectsOtherUsersPaper() throws Exception {
        Paper paper = givenIngestedPaper("graph-someone-else.pdf");
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null,
                "papers/" + paper.getId() + VIZ_SUFFIX);

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(fileStorage, never()).presignAssetGet(any());
    }
}
