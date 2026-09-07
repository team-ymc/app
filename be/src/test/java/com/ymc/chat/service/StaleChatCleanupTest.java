package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.domain.ChatSession;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

class StaleChatCleanupTest extends IntegrationTest {

    @Autowired
    StaleChatCleanup cleanup;

    @Autowired
    UsageService usageService;

    private UUID givenStaleGenerating(Instant createdAt) {
        UUID clientMessageId = UUID.randomUUID();
        ChatSession session = chatSessionRepository.save(
                ChatSession.open(TEST_USER_ID, UUID.randomUUID(), "질문", createdAt));
        chatMessageRepository.save(ChatMessage.userMessage(
                session, clientMessageId, "질문", 1, createdAt));
        chatMessageRepository.save(ChatMessage.assistantGenerating(
                session, clientMessageId, 2, createdAt));
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.AI_QUERY, clientMessageId, UsageSourceType.CHAT_MESSAGE));
        return clientMessageId;
    }

    @Test
    @DisplayName("deadline 지난 GENERATING을 FAILED로 내리고 예약을 해제한다")
    void cleansStaleGenerating() {
        UUID stale = givenStaleGenerating(Instant.now().minus(1, ChronoUnit.HOURS));
        UUID fresh = givenStaleGenerating(Instant.now());

        cleanup.run();

        assertThat(usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, stale).orElseThrow()
                .getStatus()).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, fresh).orElseThrow()
                .getStatus()).isEqualTo(UsageRecordStatus.RESERVED);
        assertThat(chatMessageRepository.findByClientMessageIdAndRole(
                stale, com.ymc.chat.domain.ChatMessageRole.ASSISTANT).orElseThrow()
                .getStatus()).isEqualTo(ChatMessageStatus.FAILED);
    }
}
