// test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java
package com.ymc.chat.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatMessageTransitions;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

/** 세션 목록·메시지 히스토리·삭제 (YMC-260). 계약 operation listChatSessions 외 2개. */
class ChatSessionHistoryIntegrationTest extends IntegrationTest {

    static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    ChatCommandService chatCommandService;

    @Autowired
    ChatMessageTransitions chatMessageTransitions;

    RequestPostProcessor otherUserJwt() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject(OTHER_USER_ID.toString()));
    }

    Paper givenCompletedPaper(UUID ownerId, String filename) {
        Paper paper = paperRepository.save(Paper.register(ownerId, filename, Instant.now()));
        paperTransitions.markUploaded(paper.getId());
        paperTransitions.markProcessing(paper.getId());
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    /** 질문 1건을 보내고 답변을 완료시켜 세션을 만든다. */
    ChatStartResult givenCompletedExchange(UUID ownerId, Paper paper, UUID sessionIdOrNull,
            String question) {
        ChatStartResult started = chatCommandService.start(
                ownerId, paper.getId(), sessionIdOrNull, UUID.randomUUID(), question);
        chatMessageTransitions.complete(started.assistantMessageId(), "답변");
        return started;
    }

    @Test
    @DisplayName("세션 목록 — 내 세션만, lastMessageAt 내림차순, title 포함")
    void listSessionsOrderedByActivity() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
        ChatStartResult older = givenCompletedExchange(TEST_USER_ID, paper, null, "첫 세션 질문");
        ChatStartResult newer = givenCompletedExchange(TEST_USER_ID, paper, null, "둘째 세션 질문");
        // older 세션에 후속 질문 — older가 최신 활동이 된다
        givenCompletedExchange(TEST_USER_ID, paper, older.sessionId(), "후속 질문");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions", paper.getId())
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sessionId").value(older.sessionId().toString()))
                .andExpect(jsonPath("$[0].title").value("첫 세션 질문"))
                .andExpect(jsonPath("$[1].sessionId").value(newer.sessionId().toString()))
                .andExpect(jsonPath("$[0].lastMessageAt").exists())
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    @DisplayName("메시지 히스토리 — seq 오름차순, status·content 보존 (GENERATING은 content null)")
    void listMessagesPreservesOrderAndStatus() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
        ChatStartResult first = givenCompletedExchange(TEST_USER_ID, paper, null, "질문1");
        // 후속 질문은 완료시키지 않는다 — GENERATING 상태 보존 검증
        chatCommandService.start(
                TEST_USER_ID, paper.getId(), first.sessionId(), UUID.randomUUID(), "질문2");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                        paper.getId(), first.sessionId())
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("질문1"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].seq").value(2))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[1].content").value("답변"))
                .andExpect(jsonPath("$[3].seq").value(4))
                .andExpect(jsonPath("$[3].status").value("GENERATING"))
                .andExpect(jsonPath("$[3].content").value(nullValue()));
    }

    @Test
    @DisplayName("타인 논문의 목록 조회는 403 FORBIDDEN")
    void listSessionsOfOthersPaperForbidden() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "mine.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions", paper.getId())
                        .with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("없는 논문의 목록 조회는 404 PAPER_NOT_FOUND")
    void listSessionsOfMissingPaperNotFound() throws Exception {
        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions", UUID.randomUUID())
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("내 논문이라도 다른 논문의 세션 히스토리는 404 CHAT_SESSION_NOT_FOUND")
    void listMessagesOfSessionFromAnotherPaperNotFound() throws Exception {
        Paper paperA = givenCompletedPaper(TEST_USER_ID, "a.pdf");
        Paper paperB = givenCompletedPaper(TEST_USER_ID, "b.pdf");
        ChatStartResult sessionOnB = givenCompletedExchange(TEST_USER_ID, paperB, null, "질문");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                        paperA.getId(), sessionOnB.sessionId())
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("타인 세션의 히스토리는 404 CHAT_SESSION_NOT_FOUND (존재를 숨긴다)")
    void listMessagesOfOthersSessionNotFound() throws Exception {
        Paper othersPaper = givenCompletedPaper(OTHER_USER_ID, "others.pdf");
        ChatStartResult othersSession =
                givenCompletedExchange(OTHER_USER_ID, othersPaper, null, "남의 질문");
        Paper myPaper = givenCompletedPaper(TEST_USER_ID, "mine.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                        myPaper.getId(), othersSession.sessionId())
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
    }
}
