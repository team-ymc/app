package com.ymc.plan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PlanEntitlement;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

class PlanApiIntegrationTest extends IntegrationTest {

    @Autowired
    UsageService usageService;

    @Test
    @DisplayName("Free 기본 — 사용량 0, 버킷을 만들지 않는다")
    void freeDefaultWithoutBucket() throws Exception {
        mockMvc.perform(get("/api/me/plan").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.planExpiresAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.usage.aiQuery.mode").value("MONTHLY"))
                .andExpect(jsonPath("$.usage.aiQuery.limit").value(100))
                .andExpect(jsonPath("$.usage.aiQuery.used").value(0))
                .andExpect(jsonPath("$.usage.aiQuery.remaining").value(100))
                .andExpect(jsonPath("$.usage.aiQuery.resetAt").exists())
                .andExpect(jsonPath("$.usage.paperRegistration.limit").value(3));

        assertThat(usageBucketRepository.count()).isZero();
    }

    @Test
    @DisplayName("used는 확정과 진행 중 예약의 합")
    void usedCountsReservedAndConfirmed() throws Exception {
        UUID confirmed = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            usageService.reserve(TEST_USER_ID, UsageType.AI_QUERY, confirmed, UsageSourceType.CHAT_MESSAGE);
            usageService.reserve(TEST_USER_ID, UsageType.AI_QUERY, UUID.randomUUID(), UsageSourceType.CHAT_MESSAGE);
        });
        tx.executeWithoutResult(s -> usageService.confirm(UsageType.AI_QUERY, confirmed, null));

        mockMvc.perform(get("/api/me/plan").with(userJwt()))
                .andExpect(jsonPath("$.usage.aiQuery.used").value(2))
                .andExpect(jsonPath("$.usage.aiQuery.remaining").value(98));
    }

    @Test
    @DisplayName("유효한 Pro는 플랜·만료 시각·Pro 한도를 반환한다")
    void proPlanWithExpiry() throws Exception {
        // PostgreSQL timestamp(6) 왕복 후에도 동일하도록 DB 정밀도에 맞춘다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant ended = now.plus(30, ChronoUnit.DAYS);
        planEntitlementRepository.save(PlanEntitlement.grant(
                TEST_USER_ID, PlanCode.PRO, now.minusSeconds(60), ended, now));

        mockMvc.perform(get("/api/me/plan").with(userJwt()))
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.planExpiresAt").value(ended.toString()))
                .andExpect(jsonPath("$.usage.aiQuery.limit").value(1000))
                .andExpect(jsonPath("$.usage.paperRegistration.limit").value(100));
    }

    @Test
    @DisplayName("미인증은 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/me/plan"))
                .andExpect(status().isUnauthorized());
    }
}
