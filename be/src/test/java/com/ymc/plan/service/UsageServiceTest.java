package com.ymc.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.support.IntegrationTest;

class UsageServiceTest extends IntegrationTest {

    @Autowired
    UsageService usageService;

    private void reserveInTx(UUID sourceId) {
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, sourceId));
    }

    @Test
    @DisplayName("예약은 RESERVED 원장을 남기고, 한도(Free 문서 3회) 초과는 429 코드")
    void reserveUntilLimit() {
        reserveInTx(UUID.randomUUID());
        reserveInTx(UUID.randomUUID());
        reserveInTx(UUID.randomUUID());

        assertThatThrownBy(() -> reserveInTx(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.PAPER_USAGE_LIMIT_EXCEEDED));
        assertThat(usageRecordRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("AI 질의 한도 초과는 CHAT_USAGE_LIMIT_EXCEEDED")
    void chatLimitCode() {
        // Free AI 질의 100회를 원장으로 채운 뒤 101번째 예약
        for (int i = 0; i < 100; i++) {
            tx.executeWithoutResult(s -> usageService.reserve(
                    TEST_USER_ID, UsageType.AI_QUERY, UUID.randomUUID()));
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.AI_QUERY, UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.CHAT_USAGE_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("같은 sourceId 재예약은 no-op — 원장이 늘지 않는다")
    void duplicateReserveIsNoop() {
        UUID sourceId = UUID.randomUUID();
        reserveInTx(sourceId);
        reserveInTx(sourceId);
        assertThat(usageRecordRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 예약 4개는 한도 3을 넘지 않는다")
    void concurrentReservesRespectLimit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    reserveInTx(UUID.randomUUID());
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(3);
        assertThat(usageRecordRepository.countByBucketIdAndStatusIn(
                usageBucketRepository.findAll().get(0).getId(),
                List.of(UsageRecordStatus.RESERVED, UsageRecordStatus.CONFIRMED)))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("동시 같은 sourceId 예약은 유니크 제약이 한쪽을 막는다")
    void concurrentSameSourceGuardedByUnique() throws Exception {
        UUID sourceId = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    reserveInTx(sourceId);
                    return true;
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(usageRecordRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("confirm은 RESERVED만 CONFIRMED로 바꾸고 비용을 저장한다")
    void confirmSettlesWithCost() {
        UUID sourceId = UUID.randomUUID();
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.AI_QUERY, sourceId));

        tx.executeWithoutResult(s -> usageService.confirm(
                UsageType.AI_QUERY, sourceId, new BigDecimal("0.00123000")));

        var record = usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, sourceId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(record.getEstimatedCostUsd()).isEqualByComparingTo("0.00123000");
    }

    @Test
    @DisplayName("release는 RESERVED를 RELEASED로 — 집계에서 빠져 재예약 여유가 생긴다")
    void releaseFreesCapacity() {
        UUID sourceId = UUID.randomUUID();
        reserveInTx(sourceId);
        reserveInTx(UUID.randomUUID());
        reserveInTx(UUID.randomUUID());

        tx.executeWithoutResult(s -> usageService.release(
                UsageType.PAPER_REGISTRATION, sourceId));

        reserveInTx(UUID.randomUUID()); // 한도 3 — 해제로 자리가 났으니 성공해야 한다
        assertThat(usageRecordRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("해제된 sourceId의 재예약은 거부된다")
    void reserveAfterReleaseRejected() {
        UUID sourceId = UUID.randomUUID();
        reserveInTx(sourceId);
        tx.executeWithoutResult(s -> usageService.release(
                UsageType.PAPER_REGISTRATION, sourceId));

        assertThatThrownBy(() -> reserveInTx(sourceId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("중복 confirm은 no-op, 원장 없는 confirm도 예외 없이 지나간다")
    void settleIsIdempotentAndTolerant() {
        UUID sourceId = UUID.randomUUID();
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.AI_QUERY, sourceId));
        tx.executeWithoutResult(s -> usageService.confirm(UsageType.AI_QUERY, sourceId, null));
        tx.executeWithoutResult(s -> usageService.confirm(UsageType.AI_QUERY, sourceId, null));
        tx.executeWithoutResult(s -> usageService.confirm(
                UsageType.AI_QUERY, UUID.randomUUID(), null)); // 원장 부재 — warn 로그만

        assertThat(usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.AI_QUERY, sourceId).orElseThrow()
                .getStatus()).isEqualTo(UsageRecordStatus.CONFIRMED);
    }
}
