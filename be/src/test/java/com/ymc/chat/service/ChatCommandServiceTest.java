// test/java/com/ymc/chat/service/ChatCommandServiceTest.java
package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.domain.ChatSession;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class ChatCommandServiceTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    @Autowired
    ChatMessageTransitions chatMessageTransitions;

    /** 파싱 완료(COMPLETED) 논문 — 채팅 가능 상태. */
    private Paper givenCompletedPaper() {
        Paper paper = givenProcessingPaper("chat-target.pdf");
        paperTransitions.markParsed(paper.getId(), com.ymc.paper.domain.PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    @Test
    @DisplayName("sessionId 없이 시작하면 세션을 만들고 user COMPLETED + assistant GENERATING을 저장한다")
    void startCreatesSessionAndRows() {
        Paper paper = givenCompletedPaper();

        ChatStartResult result = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "핵심 기여가 뭐야?");

        ChatSession session = chatSessionRepository.findById(result.sessionId()).orElseThrow();
        assertThat(session.getPaperId()).isEqualTo(paper.getId());
        assertThat(session.getOwnerId()).isEqualTo(TEST_USER_ID);

        ChatMessage assistant = chatMessageRepository.findById(result.assistantMessageId()).orElseThrow();
        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.GENERATING);
        assertThat(assistant.getRole()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("기존 sessionId로 시작하면 같은 세션에 메시지가 쌓인다")
    void startReusesSession() {
        Paper paper = givenCompletedPaper();
        ChatStartResult first = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "첫 질문");
        // 첫 assistant를 종결시켜 CHAT_RUN_IN_PROGRESS를 피한다
        // (@Modifying 쿼리는 트랜잭션이 필요하므로 리포지토리 직접 호출이 아니라 transitions 빈을 쓴다)
        chatMessageTransitions.complete(first.assistantMessageId(), "답");

        ChatStartResult second = chatCommandService.start(
                TEST_USER_ID, paper.getId(), first.sessionId(), UUID.randomUUID(), "후속 질문");

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(chatMessageRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("남의 세션이나 다른 논문의 세션이면 CHAT_SESSION_NOT_FOUND")
    void rejectsForeignSession() {
        Paper paper = givenCompletedPaper();
        ChatSession otherOwners = chatSessionRepository.save(
                ChatSession.open(UUID.randomUUID(), paper.getId(), Instant.now()));

        assertThatThrownBy(() -> chatCommandService.start(
                TEST_USER_ID, paper.getId(), otherOwners.getId(), UUID.randomUUID(), "질문"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("세션에 GENERATING assistant가 있으면 CHAT_RUN_IN_PROGRESS")
    void rejectsConcurrentRun() {
        Paper paper = givenCompletedPaper();
        ChatStartResult first = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "첫 질문");

        assertThatThrownBy(() -> chatCommandService.start(
                TEST_USER_ID, paper.getId(), first.sessionId(), UUID.randomUUID(), "성급한 질문"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CHAT_RUN_IN_PROGRESS));
    }

    @Test
    @DisplayName("같은 clientMessageId·같은 content 재전송은 기존 식별자·상태를 담은 예외 — 새 행을 만들지 않는다")
    void duplicateSameContentIsIdempotent() {
        Paper paper = givenCompletedPaper();
        UUID clientMessageId = UUID.randomUUID();
        ChatStartResult first = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "같은 질문");

        DuplicateChatMessageException dup = catchThrowableOfType(
                DuplicateChatMessageException.class,
                () -> chatCommandService.start(
                        TEST_USER_ID, paper.getId(), first.sessionId(), clientMessageId, "같은 질문"));

        assertThat(dup.getSessionId()).isEqualTo(first.sessionId());
        assertThat(dup.getMessageId()).isEqualTo(first.assistantMessageId());
        assertThat(dup.getStatus()).isEqualTo(ChatMessageStatus.GENERATING);
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 clientMessageId를 다른 content에 재사용하면 CLIENT_MESSAGE_ID_CONFLICT")
    void duplicateDifferentContentConflicts() {
        Paper paper = givenCompletedPaper();
        UUID clientMessageId = UUID.randomUUID();
        chatCommandService.start(TEST_USER_ID, paper.getId(), null, clientMessageId, "원래 질문");

        assertThatThrownBy(() -> chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "다른 질문"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CLIENT_MESSAGE_ID_CONFLICT));
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }
}
