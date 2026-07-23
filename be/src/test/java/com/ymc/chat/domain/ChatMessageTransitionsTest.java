package com.ymc.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.ymc.chat.service.ChatMessageTransitions;
import com.ymc.support.IntegrationTest;

class ChatMessageTransitionsTest extends IntegrationTest {

    @Autowired
    ChatMessageTransitions transitions;

    private ChatMessage givenGeneratingAssistant() {
        ChatSession session = chatSessionRepository.save(
                ChatSession.open(TEST_USER_ID, UUID.randomUUID(), Instant.now()));
        return chatMessageRepository.save(
                ChatMessage.assistantGenerating(session, UUID.randomUUID(), Instant.now()));
    }

    @Test
    @DisplayName("GENERATING → COMPLETED 전이는 한 번만 성공하고 content를 저장한다")
    void completeOnlyOnce() {
        ChatMessage assistant = givenGeneratingAssistant();

        assertThat(transitions.complete(assistant.getId(), "최종 답변")).isTrue();
        assertThat(transitions.complete(assistant.getId(), "다른 답변")).isFalse();
        assertThat(transitions.fail(assistant.getId())).isFalse();

        ChatMessage saved = chatMessageRepository.findById(assistant.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(saved.getContent()).isEqualTo("최종 답변");
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("GENERATING → FAILED 전이 후에는 COMPLETED가 불가능하고 content는 남지 않는다")
    void failBlocksComplete() {
        ChatMessage assistant = givenGeneratingAssistant();

        assertThat(transitions.fail(assistant.getId())).isTrue();
        assertThat(transitions.complete(assistant.getId(), "늦은 답변")).isFalse();

        ChatMessage saved = chatMessageRepository.findById(assistant.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(saved.getContent()).isNull();
    }

    @Test
    @DisplayName("같은 clientMessageId·같은 role은 유니크 제약에 걸린다")
    void clientMessageIdUniquePerRole() {
        ChatSession session = chatSessionRepository.save(
                ChatSession.open(TEST_USER_ID, UUID.randomUUID(), Instant.now()));
        UUID clientMessageId = UUID.randomUUID();
        chatMessageRepository.saveAndFlush(
                ChatMessage.userMessage(session, clientMessageId, "질문", Instant.now()));

        assertThatThrownBy(() -> chatMessageRepository.saveAndFlush(
                ChatMessage.userMessage(session, clientMessageId, "질문", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
