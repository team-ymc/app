package com.ymc.paper.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("완료된 논문: 200과 다운로드 URL")
    void returnsDownloadUrlForCompletedPaper() throws Exception {
        Paper paper = givenProcessingPaper("done.pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty());
    }

    @Test
    @DisplayName("업로드 전(UPLOAD_PENDING): 409 UPLOAD_NOT_FOUND")
    void rejectsPendingPaper() throws Exception {
        Paper paper = givenPendingPaper("pending.pdf");

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 paperId: 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/download", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("남의 논문: presigned URL을 발급하지 않고 403 FORBIDDEN")
    void rejectsOtherUsersPaper() throws Exception {
        Paper paper = givenProcessingPaper("someone-else.pdf");

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()).with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(fileStorage, never()).presignDownload(any(), any());
    }

    @Test
    @DisplayName("UUID가 아닌 paperId: 400 VALIDATION_ERROR")
    void rejectsMalformedPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/download", "not-a-uuid").with(userJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
