package com.ymc.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PlanEntitlement;
import com.ymc.support.IntegrationTest;

class PlanServiceTest extends IntegrationTest {

    @Autowired
    PlanService planService;

    @Test
    @DisplayName("권한 기록이 없으면 Free")
    void defaultIsFree() {
        assertThat(planService.effectivePlan(TEST_USER_ID, Instant.now()))
                .isEqualTo(PlanCode.FREE);
    }

    @Test
    @DisplayName("유효 기간 안이면 Pro, 만료 시각부터 Free — [started, ended) 경계")
    void proWithinPeriodBoundary() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.DAYS);
        Instant ended = started.plus(30, ChronoUnit.DAYS);
        planEntitlementRepository.save(PlanEntitlement.grant(
                TEST_USER_ID, PlanCode.PRO, started, ended, started));

        assertThat(planService.effectivePlan(TEST_USER_ID, started)).isEqualTo(PlanCode.PRO);
        assertThat(planService.effectivePlan(TEST_USER_ID, ended.minusMillis(1)))
                .isEqualTo(PlanCode.PRO);
        assertThat(planService.effectivePlan(TEST_USER_ID, ended)).isEqualTo(PlanCode.FREE);
        assertThat(planService.proExpiresAt(TEST_USER_ID, started)).contains(ended);
    }

    @Test
    @DisplayName("겹치는 권한이 여럿이면 가장 늦은 만료 시각")
    void latestExpiryWins() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant endedShort = now.plus(7, ChronoUnit.DAYS);
        Instant endedLong = now.plus(30, ChronoUnit.DAYS);
        planEntitlementRepository.save(PlanEntitlement.grant(
                TEST_USER_ID, PlanCode.PRO, now.minusSeconds(60), endedShort, now));
        planEntitlementRepository.save(PlanEntitlement.grant(
                TEST_USER_ID, PlanCode.PRO, now.minusSeconds(60), endedLong, now));

        assertThat(planService.proExpiresAt(TEST_USER_ID, now)).contains(endedLong);
    }
}
