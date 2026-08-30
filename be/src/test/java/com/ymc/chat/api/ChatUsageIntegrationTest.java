package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatMessageTransitions;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageBucket;
import com.ymc.plan.domain.UsageRecord;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.support.IntegrationTest;

class ChatUsageIntegrationTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    @Autowired
    ChatMessageTransitions chatMessageTransitions;

    Paper givenCompletedPaper(String filename) {
        Paper paper = paperRepository.save(Paper.register(TEST_USER_ID, filename, Instant.now()));
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    @Test
    @DisplayName("start는 RESERVED를 남기고, 완료 전이가 CONFIRMED와 비용을 저장한다")
    void startReservesAndCompleteConfirms() {
        Paper paper = givenCompletedPaper("usage.pdf");
        UUID clientMessageId = UUID.randomUUID();
        ChatStartResult started = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "질문");

        UsageRecord reserved = usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, clientMessageId).orElseThrow();
        assertThat(reserved.getStatus()).isEqualTo(UsageRecordStatus.RESERVED);

        chatMessageTransitions.complete(started.assistantMessageId(), "답변",
                clientMessageId, new java.math.BigDecimal("0.001"));

        UsageRecord confirmed = usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, clientMessageId).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(confirmed.getEstimatedCostUsd()).isEqualByComparingTo("0.001");
    }

    @Test
    @DisplayName("범위 초과 비용은 버리고 답변 COMPLETED와 사용량 CONFIRMED를 유지한다")
    void oversizedCostDoesNotFailCompletedAnswer() {
        Paper paper = givenCompletedPaper("oversized-cost.pdf");
        UUID clientMessageId = UUID.randomUUID();
        ChatStartResult started = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "질문");

        boolean completed = chatMessageTransitions.complete(
                started.assistantMessageId(), "답변", clientMessageId, new BigDecimal("1000000"));

        assertThat(completed).isTrue();
        var message = chatMessageRepository.findById(started.assistantMessageId()).orElseThrow();
        assertThat(message.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(message.getContent()).isEqualTo("답변");
        UsageRecord confirmed = usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, clientMessageId).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(confirmed.getEstimatedCostUsd()).isNull();
    }

    @Test
    @DisplayName("실패 전이는 예약을 해제한다")
    void failReleases() {
        Paper paper = givenCompletedPaper("usage.pdf");
        UUID clientMessageId = UUID.randomUUID();
        ChatStartResult started = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "질문");

        chatMessageTransitions.fail(started.assistantMessageId(), clientMessageId);

        assertThat(usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, clientMessageId).orElseThrow()
                .getStatus()).isEqualTo(UsageRecordStatus.RELEASED);
    }

    @Test
    @DisplayName("한도를 채우면 start가 429로 거절되고 메시지 row가 생기지 않는다")
    void startRejectedAtLimit() throws Exception {
        Paper paper = givenCompletedPaper("usage.pdf");
        // Free AI 질의 100회를 원장으로 직접 채운다
        Instant now = Instant.now();
        UsageBucket bucket = usageBucketRepository.save(UsageBucket.open(
                TEST_USER_ID, UsageType.AI_QUERY, com.ymc.plan.domain.PlanCode.FREE,
                com.ymc.plan.service.BucketPeriod.startOf(now), now));
        for (int i = 0; i < 100; i++) {
            usageRecordRepository.save(UsageRecord.reserve(
                    bucket.getId(), UsageType.AI_QUERY, UUID.randomUUID(), now));
        }

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"%s","content":"질문"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(MockMvcResultMatchers.status().isTooManyRequests())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("CHAT_USAGE_LIMIT_EXCEEDED"));
        assertThat(chatMessageRepository.count()).isZero();
    }
}
