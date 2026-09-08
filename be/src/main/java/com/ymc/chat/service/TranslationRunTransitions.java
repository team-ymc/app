package com.ymc.chat.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.TranslationRunRepository;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;

import lombok.RequiredArgsConstructor;

/** 번역 run의 종결 전이만 담는 트랜잭션 단위. 조건부 UPDATE라 완료·실패가 경쟁해도 한쪽만 1 row를 얻는다. */
@Service
@RequiredArgsConstructor
public class TranslationRunTransitions {

    private final TranslationRunRepository translationRunRepository;
    private final UsageService usageService;

    /** @return 이 호출이 COMPLETED 전이의 주인이면 true. 주인일 때만 사용량을 확정한다. */
    @Transactional
    public boolean complete(UUID translationId, String translation, BigDecimal estimatedCostUsd) {
        boolean owner = translationRunRepository.markCompleted(translationId, translation, Instant.now()) == 1;
        if (owner) {
            usageService.confirm(UsageType.AI_QUERY, translationId, estimatedCostUsd);
        }
        return owner;
    }

    /** @return 이 호출이 FAILED 전이의 주인이면 true. 주인일 때만 예약을 해제한다. */
    @Transactional
    public boolean fail(UUID translationId) {
        boolean owner = translationRunRepository.markFailed(translationId, Instant.now()) == 1;
        if (owner) {
            usageService.release(UsageType.AI_QUERY, translationId);
        }
        return owner;
    }
}
