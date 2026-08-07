package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatSelection;
import com.ymc.chat.service.ChatCommandService;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

/** selection 저장·이력 응답 round-trip. AI 스트림은 다루지 않는다 — 시작 트랜잭션까지만. */
class ChatSelectionPersistenceTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    private Paper givenCompletedPaper() {
        Paper paper = givenProcessingPaper("selection-" + UUID.randomUUID() + ".pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    @Test
    @DisplayName("selection이 user 메시지에 저장되고 이력 응답에 camelCase로 포함된다")
    void selectionRoundTrip() throws Exception {
        Paper paper = givenCompletedPaper();
        ChatSelection selection = new ChatSelection(
                new ChatSelection.Anchor("p0002-b0000", null),
                new ChatSelection.Anchor("p0002-b0003", null));

        var started = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "이 부분 설명해줘", selection);

        ChatMessage user = chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.USER).findFirst().orElseThrow();
        assertThat(user.getSelection()).isEqualTo(selection);

        String body = mockMvc.perform(
                        get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                                paper.getId(), started.sessionId()).with(userJwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"blockId\":\"p0002-b0000\"");
        assertThat(body).contains("\"selection\":null"); // assistant 행은 null
    }

    @Test
    @DisplayName("selection 없는 질문은 selection null로 저장된다")
    void nullSelection() {
        Paper paper = givenCompletedPaper();
        chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "전체 요약해줘", null);
        ChatMessage user = chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.USER).findFirst().orElseThrow();
        assertThat(user.getSelection()).isNull();
    }

    @Test
    @DisplayName("같은 clientMessageId·content라도 selection이 다르면 CLIENT_MESSAGE_ID_CONFLICT다")
    void differentSelectionConflicts() {
        Paper paper = givenCompletedPaper();
        UUID clientMessageId = UUID.randomUUID();
        ChatSelection first = new ChatSelection(
                new ChatSelection.Anchor("p0001-b0000", null),
                new ChatSelection.Anchor("p0001-b0000", null));
        chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "이 부분 설명해줘", first);

        ChatSelection other = new ChatSelection(
                new ChatSelection.Anchor("p0001-b0003", null),
                new ChatSelection.Anchor("p0001-b0003", null));
        assertThatThrownBy(() -> chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "이 부분 설명해줘", other))
                .isInstanceOf(com.ymc.common.error.ApiException.class)
                .hasMessageContaining("clientMessageId");
    }
}
