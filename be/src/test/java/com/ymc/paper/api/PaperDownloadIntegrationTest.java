package com.ymc.paper.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

/** spec: paper-download (Task 3). */
class PaperDownloadIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("업로드된(PROCESSING) 논문: 200과 {downloadUrl, expiresAt}")
    void returnsDownloadUrlForUploadedPaper() throws Exception {
        Paper paper = givenProcessingPaper("attention.pdf");

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("완료된 논문: 200과 다운로드 URL")
    void returnsDownloadUrlForCompletedPaper() throws Exception {
        Paper paper = givenProcessingPaper("done.pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty());
    }

    @Test
    @DisplayName("업로드 전(UPLOAD_PENDING): 409 UPLOAD_NOT_FOUND")
    void rejectsPendingPaper() throws Exception {
        Paper paper = givenPendingPaper("pending.pdf");

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 paperId: 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/download", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("UUID가 아닌 paperId: 400 VALIDATION_ERROR")
    void rejectsMalformedPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/download", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
