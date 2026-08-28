package com.ymc.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BucketPeriodTest {

    @Test
    @DisplayName("KST 월 시작 — 8월 중순은 7월 31일 15:00Z(=KST 8/1 00:00)")
    void startOfMidMonth() {
        Instant at = Instant.parse("2026-08-15T03:00:00Z");
        assertThat(BucketPeriod.startOf(at)).isEqualTo(Instant.parse("2026-07-31T15:00:00Z"));
    }

    @Test
    @DisplayName("KST 자정 직전·직후로 버킷이 갈린다")
    void kstMidnightBoundary() {
        assertThat(BucketPeriod.startOf(Instant.parse("2026-08-31T14:59:59Z")))
                .isEqualTo(Instant.parse("2026-07-31T15:00:00Z"));
        assertThat(BucketPeriod.startOf(Instant.parse("2026-08-31T15:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-31T15:00:00Z"));
    }

    @Test
    @DisplayName("연말 경계 — 12월 버킷의 다음 초기화는 KST 1/1 00:00")
    void yearBoundaryReset() {
        Instant at = Instant.parse("2026-12-15T00:00:00Z");
        assertThat(BucketPeriod.nextResetAfter(at)).isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));
    }
}
