package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PaperExpiryIntegrationTest extends IntegrationTest {

    private Paper givenExpiredPaper(String filename) {
        Paper paper = givenPendingPaper(filename);
        tx.executeWithoutResult(s ->
                paperRepository.markExpired(paper.getId(), Instant.now()));
        return reload(paper.getId());
    }

    @Test
    @DisplayName("만료된 Paper의 상태는 EXPIRED로 파생된다")
    void expiredStatusDerived() throws Exception {
        Paper paper = givenExpiredPaper("expired.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("document가 연결된 Paper는 만료 CAS가 0행이다")
    void expireCasSkipsLinkedPaper() {
        Paper paper = givenPendingPaper("linked.pdf");
        givenLinkedDocument(paper);

        Integer updated = tx.execute(s ->
                paperRepository.markExpired(paper.getId(), Instant.now()));

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("만료된 Paper의 complete는 409 UPLOAD_EXPIRED")
    void completeRejectedAfterExpiry() throws Exception {
        Paper paper = givenExpiredPaper("expired.pdf");
        givenUploadedObject(paper);

        mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_EXPIRED"));
    }

    @Test
    @DisplayName("같은 파일명 재등록이 만료 row를 대체한다")
    @Disabled("Task 7에서 활성화")
    void reregisterReplacesExpiredRow() throws Exception {
        Paper expired = givenExpiredPaper("retry.pdf");

        mockMvc.perform(post("/api/papers")
                        .contentType("application/json")
                        .content(createPaperJson("retry.pdf"))
                        .with(userJwt()))
                .andExpect(status().isCreated());

        assertThat(paperRepository.findById(expired.getId())).isEmpty();
        assertThat(paperRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 안 된 같은 파일명은 여전히 DUPLICATE_FILENAME")
    @Disabled("Task 7에서 활성화")
    void nonExpiredDuplicateStillRejected() throws Exception {
        givenPendingPaper("dup.pdf");

        mockMvc.perform(post("/api/papers")
                        .contentType("application/json")
                        .content(createPaperJson("dup.pdf"))
                        .with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FILENAME"));
    }
}
