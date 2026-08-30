package com.ymc.chat.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;

import lombok.RequiredArgsConstructor;

/**
 * assistant 메시지의 종결 전이만 담는 트랜잭션 단위. relay 스레드가 호출한다.
 * 조건부 UPDATE라 완료·실패가 경쟁해도 한쪽만 1 row를 얻는다 (설계 §3 D6).
 */
@Service
@RequiredArgsConstructor
public class ChatMessageTransitions {

    private final ChatMessageRepository chatMessageRepository;
    private final UsageService usageService;

    /** @return 이 호출이 COMPLETED 전이의 주인이면 true. 주인일 때만 사용량을 확정한다. */
    @Transactional
    public boolean complete(UUID messageId, String content, UUID clientMessageId,
            BigDecimal estimatedCostUsd) {
        boolean owner = chatMessageRepository.markCompleted(messageId, content, Instant.now()) == 1;
        if (owner) {
            usageService.confirm(UsageType.AI_QUERY, clientMessageId, estimatedCostUsd);
        }
        return owner;
    }

    /** @return 이 호출이 FAILED 전이의 주인이면 true. 주인일 때만 예약을 해제한다. */
    @Transactional
    public boolean fail(UUID messageId, UUID clientMessageId) {
        boolean owner = chatMessageRepository.markFailed(messageId, Instant.now()) == 1;
        if (owner) {
            usageService.release(UsageType.AI_QUERY, clientMessageId);
        }
        return owner;
    }
}
