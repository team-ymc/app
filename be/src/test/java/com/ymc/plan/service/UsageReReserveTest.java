package com.ymc.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.plan.domain.UsageRecord;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.support.IntegrationTest;

class UsageReReserveTest extends IntegrationTest {

    @Autowired
    UsageService usageService;

    private void reserveInTx(UUID sourceId) {
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, sourceId, UsageSourceType.PAPER));
    }

    private void reReserveInTx(UUID sourceId) {
        tx.executeWithoutResult(s -> usageService.reReserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, sourceId));
    }

    private UUID givenReleased() {
        UUID sourceId = UUID.randomUUID();
        reserveInTx(sourceId);
        tx.executeWithoutResult(s -> usageService.release(UsageType.PAPER_REGISTRATION, sourceId));
        return sourceId;
    }

    private UsageRecord recordOf(UUID sourceId) {
        return usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.PAPER_REGISTRATION, sourceId).orElseThrow();
    }

    @Test
    @DisplayName("환불된 기록은 다시 예약된다")
    void releasedBecomesReserved() {
        UUID sourceId = givenReleased();

        reReserveInTx(sourceId);

        assertThat(recordOf(sourceId).getStatus()).isEqualTo(UsageRecordStatus.RESERVED);
        assertThat(usageRecordRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("한도가 찼으면 거절하고 기록은 환불된 채로 둔다")
    void rejectsAtLimit() {
        UUID sourceId = givenReleased();
        reserveInTx(UUID.randomUUID());
        reserveInTx(UUID.randomUUID());
        reserveInTx(UUID.randomUUID());

        assertThatThrownBy(() -> reReserveInTx(sourceId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.PAPER_USAGE_LIMIT_EXCEEDED));
        assertThat(recordOf(sourceId).getStatus()).isEqualTo(UsageRecordStatus.RELEASED);
    }

    @Test
    @DisplayName("지난달에 환불된 기록은 이번 달 버킷으로 옮겨 집계한다")
    void movesToCurrentMonthBucket() {
        UUID sourceId = givenReleased();
        UUID lastMonthBucket = recordOf(sourceId).getBucketId();
        jdbcTemplate.update(
                "update usage_bucket set bucket_start = bucket_start - interval '1 month' where id = ?",
                lastMonthBucket);

        reReserveInTx(sourceId);

        UUID currentBucket = recordOf(sourceId).getBucketId();
        assertThat(currentBucket).isNotEqualTo(lastMonthBucket);
        assertThat(usageRecordRepository.countByBucketIdAndStatusIn(currentBucket,
                List.of(UsageRecordStatus.RESERVED, UsageRecordStatus.CONFIRMED))).isEqualTo(1);
        assertThat(usageRecordRepository.countByBucketIdAndStatusIn(lastMonthBucket,
                List.of(UsageRecordStatus.RESERVED, UsageRecordStatus.CONFIRMED))).isZero();
    }

    @Test
    @DisplayName("예약·확정된 기록에는 아무것도 하지 않는다")
    void activeRecordIsNoop() {
        UUID reserved = UUID.randomUUID();
        reserveInTx(reserved);
        UUID confirmed = UUID.randomUUID();
        reserveInTx(confirmed);
        tx.executeWithoutResult(s ->
                usageService.confirm(UsageType.PAPER_REGISTRATION, confirmed, null));

        reReserveInTx(reserved);
        reReserveInTx(confirmed);

        assertThat(recordOf(reserved).getStatus()).isEqualTo(UsageRecordStatus.RESERVED);
        assertThat(recordOf(confirmed).getStatus()).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    @Test
    @DisplayName("기록이 없으면 예외다")
    void missingRecordThrows() {
        assertThatThrownBy(() -> reReserveInTx(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재예약할 사용량 기록이 없습니다");
    }

    @Test
    @DisplayName("reserve는 여전히 환불된 기록의 재예약을 거부한다")
    void reserveStillRejectsReleased() {
        UUID sourceId = givenReleased();

        assertThatThrownBy(() -> reserveInTx(sourceId))
                .isInstanceOf(IllegalStateException.class);
    }
}
