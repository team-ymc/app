// test/java/com/ymc/chat/api/ChatMessageStreamIntegrationTest.java
package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

class ChatMessageStreamIntegrationTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    private Paper givenCompletedPaper() {
        Paper paper = givenProcessingPaper("chat-e2e.pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    private String body(UUID sessionId, UUID clientMessageId, String content) throws Exception {
        // Map.of는 null 값을 허용하지 않으므로 ObjectNode로 조립한다 — sessionId null이면 키를 뺀다
        var node = objectMapper.createObjectNode()
                .put("clientMessageId", clientMessageId.toString())
                .put("content", content);
        if (sessionId != null) {
            node.put("sessionId", sessionId.toString());
        }
        return objectMapper.writeValueAsString(node);
    }

    private MvcResult startStream(Paper paper, String bodyJson) throws Exception {
        return mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    @DisplayName("성공 스트림 — started → delta×3 → completed 순서로 오고 DB는 COMPLETED다")
    void successStream() throws Exception {
        Paper paper = givenCompletedPaper();
        MvcResult result = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "clientMessageId", UUID.randomUUID().toString(),
                "content", "핵심 기여가 뭐야?")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                chatMessageRepository.findAll().stream()
                        .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT)
                        .findFirst().orElseThrow().getStatus())
                .isEqualTo(ChatMessageStatus.COMPLETED));

        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        int startedAt = stream.indexOf("event:message.started");
        int firstDeltaAt = stream.indexOf("event:message.delta");
        int completedAt = stream.indexOf("event:message.completed");
        assertThat(startedAt).isNotNegative();
        assertThat(firstDeltaAt).isGreaterThan(startedAt);
        assertThat(completedAt).isGreaterThan(firstDeltaAt);
        assertThat(stream).contains("\"content\":\"가짜 응답입니다.\"");

        ChatMessage assistant = chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT).findFirst().orElseThrow();
        assertThat(assistant.getContent()).isEqualTo("가짜 응답입니다.");
    }

    @Test
    @DisplayName("AI 콜백 시점에 user·assistant row는 이미 commit돼 있다 (commit 뒤 message.started 규칙)")
    void rowsCommittedBeforeStreamStarts() throws Exception {
        Paper paper = givenCompletedPaper();
        // 포트 호출 시점(=message.started 이후)에 별도 스레드가 DB를 읽어도 행이 보여야 한다
        doAnswer(invocation -> {
            assertThat(chatMessageRepository.count()).isEqualTo(2);
            return invocation.callRealMethod();
        }).when(aiAgentStreamPort).stream(any(), any(AiStreamListener.class));

        MvcResult result = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "clientMessageId", UUID.randomUUID().toString(),
                "content", "질문")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(chatMessageRepository.findAll().stream()
                        .anyMatch(m -> m.getStatus() == ChatMessageStatus.COMPLETED
                                && m.getRole() == ChatMessageRole.ASSISTANT)).isTrue());
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AI run 실패 — FAILED 저장 후 terminal error(AI_RUN_FAILED)를 보낸다")
    void aiRunFailure() throws Exception {
        Paper paper = givenCompletedPaper();
        doAnswer(invocation -> {
            AiStreamListener listener = invocation.getArgument(1);
            Thread.startVirtualThread(() -> {
                listener.onRunStarted();
                listener.onDelta("일부");
                listener.onRunFailed("upstream raw error");
            });
            return (com.ymc.chat.service.port.AiRunHandle) () -> { };
        }).when(aiAgentStreamPort).stream(any(), any(AiStreamListener.class));

        MvcResult result = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "clientMessageId", UUID.randomUUID().toString(),
                "content", "질문")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                chatMessageRepository.findAll().stream()
                        .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT)
                        .findFirst().orElseThrow().getStatus())
                .isEqualTo(ChatMessageStatus.FAILED));

        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("event:error");
        assertThat(stream).contains("\"code\":\"AI_RUN_FAILED\"");
        assertThat(stream).doesNotContain("upstream raw error"); // raw error 미노출 (계약)

        // partial delta는 저장되지 않는다
        ChatMessage assistant = chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT).findFirst().orElseThrow();
        assertThat(assistant.getContent()).isNull();
    }

    @Test
    @DisplayName("인증 없으면 401")
    void unauthorized() throws Exception {
        Paper paper = givenCompletedPaper();
        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, UUID.randomUUID(), "질문")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("없는 논문이면 404 PAPER_NOT_FOUND")
    void paperNotFound() throws Exception {
        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", UUID.randomUUID())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, UUID.randomUUID(), "질문")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("남의 논문이면 403 FORBIDDEN")
    void foreignPaper() throws Exception {
        Paper others = paperRepository.save(com.ymc.paper.domain.Paper.register(
                UUID.randomUUID(), "others.pdf", Instant.now()));
        paperTransitions.markUploaded(others.getId());
        paperTransitions.markProcessing(others.getId());
        paperTransitions.markParsed(others.getId(), PaperStatus.COMPLETED, null);

        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", others.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, UUID.randomUUID(), "질문")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("파싱 미완료 논문이면 409 PAPER_NOT_READY")
    void paperNotReady() throws Exception {
        Paper processing = givenProcessingPaper("not-ready.pdf");
        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", processing.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, UUID.randomUUID(), "질문")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_READY"));
    }

    @Test
    @DisplayName("남의 세션이면 404 CHAT_SESSION_NOT_FOUND")
    void foreignSession() throws Exception {
        Paper paper = givenCompletedPaper();
        var foreign = chatSessionRepository.save(com.ymc.chat.domain.ChatSession.open(
                UUID.randomUUID(), paper.getId(), Instant.now()));

        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(foreign.getId(), UUID.randomUUID(), "질문")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 clientMessageId·같은 content 재전송이면 409 DUPLICATE_MESSAGE + 기존 식별자")
    void duplicateResend() throws Exception {
        Paper paper = givenCompletedPaper();
        UUID clientMessageId = UUID.randomUUID();
        ChatStartResult first = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "같은 질문");

        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, clientMessageId, "같은 질문")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MESSAGE"))
                .andExpect(jsonPath("$.sessionId").value(first.sessionId().toString()))
                .andExpect(jsonPath("$.messageId").value(first.assistantMessageId().toString()))
                .andExpect(jsonPath("$.status").value("GENERATING"));

        assertThat(chatMessageRepository.count()).isEqualTo(2); // 새 행 없음
    }

    @Test
    @DisplayName("세션 연속성 — 완료된 스트림의 sessionId로 후속 질문이 같은 세션에 쌓인다")
    void sessionContinuity() throws Exception {
        Paper paper = givenCompletedPaper();
        UUID firstClientMessageId = UUID.randomUUID();
        MvcResult first = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "clientMessageId", firstClientMessageId.toString(),
                "content", "첫 질문")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                chatMessageRepository.findByClientMessageIdAndRole(
                        firstClientMessageId, ChatMessageRole.ASSISTANT)
                        .orElseThrow().getStatus())
                .isEqualTo(ChatMessageStatus.COMPLETED));
        mockMvc.perform(asyncDispatch(first));
        UUID sessionId = chatSessionRepository.findAll().get(0).getId();

        MvcResult second = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "sessionId", sessionId.toString(),
                "clientMessageId", UUID.randomUUID().toString(),
                "content", "후속 질문")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(chatMessageRepository.count()).isEqualTo(4));
        mockMvc.perform(asyncDispatch(second));

        assertThat(chatSessionRepository.count()).isEqualTo(1);
    }
}
