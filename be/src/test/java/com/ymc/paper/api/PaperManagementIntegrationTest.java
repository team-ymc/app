package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.ymc.chat.domain.ChatSession;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PaperManagementIntegrationTest extends IntegrationTest {

    private static String renameJson(String filename) {
        return "{\"filename\":\"" + filename + "\"}";
    }

    // ---- DELETE ----

    @Test
    @DisplayName("삭제: 204, 목록에서 빠지고 소속 채팅 세션도 논리 삭제된다")
    void deletesPaperAndSessions() throws Exception {
        Paper paper = givenProcessingPaper("bye.pdf");
        ChatSession session = chatSessionRepository.save(
                ChatSession.open(TEST_USER_ID, paper.getId(), "질문", Instant.now()));

        mockMvc.perform(delete("/api/papers/{id}", paper.getId()).with(userJwt()))
                .andExpect(status().isNoContent());

        assertThat(reload(paper.getId()).getDeletedAt()).isNotNull();
        assertThat(chatSessionRepository.findById(session.getId()).orElseThrow().isDeleted()).isTrue();
        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(jsonPath("$.papers").isEmpty());
    }

    @Test
    @DisplayName("삭제: 타인 소유는 403")
    void deleteForbiddenForOtherUser() throws Exception {
        Paper paper = givenProcessingPaper("mine.pdf");

        mockMvc.perform(delete("/api/papers/{id}", paper.getId()).with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(reload(paper.getId()).getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("삭제: 없는 논문과 이미 삭제된 논문은 404")
    void deleteNotFound() throws Exception {
        Paper paper = givenProcessingPaper("twice.pdf");
        mockMvc.perform(delete("/api/papers/{id}", paper.getId()).with(userJwt()))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/papers/{id}", paper.getId()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
        mockMvc.perform(delete("/api/papers/{id}", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound());
    }

    // ---- PATCH ----

    @Test
    @DisplayName("이름 변경: 200으로 바뀐 행을 돌려주고 updatedAt은 그대로다")
    void renamesPaper() throws Exception {
        Paper paper = givenProcessingPaper("old.pdf");
        Instant updatedBefore = paper.getUpdatedAt();

        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("  new name.pdf  "))
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.filename").value("new name.pdf"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        Paper reloaded = reload(paper.getId());
        assertThat(reloaded.getFilename()).isEqualTo("new name.pdf");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedBefore);
    }

    @Test
    @DisplayName("이름 변경: 공백·256자는 400 VALIDATION_ERROR")
    void renameValidation() throws Exception {
        Paper paper = givenProcessingPaper("old.pdf");

        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("   "))
                        .with(userJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("a".repeat(256)))
                        .with(userJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(reload(paper.getId()).getFilename()).isEqualTo("old.pdf");
    }

    @Test
    @DisplayName("이름 변경: 앞뒤 공백을 뺀 길이가 255자면 허용한다")
    void renameAllowsMaxLengthAfterTrim() throws Exception {
        Paper paper = givenProcessingPaper("old.pdf");
        String name = "b".repeat(255);

        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("  " + name + "  "))
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value(name));
    }

    @Test
    @DisplayName("이름 변경: 같은 이름의 다른 논문이 있어도 허용한다")
    void renameAllowsDuplicate() throws Exception {
        givenProcessingPaper("same.pdf");
        Paper other = givenProcessingPaper("other.pdf");

        mockMvc.perform(patch("/api/papers/{id}", other.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("same.pdf"))
                        .with(userJwt()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("이름 변경: 타인 403, 삭제됨·없음 404")
    void renameForbiddenAndNotFound() throws Exception {
        Paper paper = givenProcessingPaper("x.pdf");
        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("y.pdf"))
                        .with(otherUserJwt()))
                .andExpect(status().isForbidden());

        tx.executeWithoutResult(s -> paperRepository.markDeleted(paper.getId(), Instant.now()));
        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("y.pdf"))
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }
}
