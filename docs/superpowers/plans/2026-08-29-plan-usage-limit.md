# 플랜·사용량 제한 BE (YMC-344) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Free·Pro 플랜 판정, 사용량 예약·확정·해제, `/api/me/plan`, 정체 레코드 정리 스케줄러를 BE에 구현한다.

**Architecture:** 신규 `com.ymc.plan` 도메인(엔티티 3개 + `PlanService`/`UsageService`)을 만들고, chat·paper의 기존 트랜잭션 경계(시작 트랜잭션·종결 CAS)에 예약·정산 훅을 끼운다. 정리 스케줄러는 각 도메인에 컴포넌트로 둔다.

**Tech Stack:** Spring Boot + JPA + PostgreSQL(Testcontainers 통합 테스트), `@Scheduled`(신규 도입).

**Spec:** `docs/superpowers/specs/2026-08-28-plan-usage-limit-design.md` — 이 plan의 모든 판단 근거. 충돌 시 spec이 이긴다.

## Global Constraints

- 커밋: `[YMC-344] type(scope): subject`. Claude attribution 금지.
- 계약 SSOT: `project-docs/contracts/frontend-backend/openapi.yaml`. `CHAT_USAGE_LIMIT_EXCEEDED`·`PAPER_USAGE_LIMIT_EXCEEDED`(429)는 계약에 이미 있음. **`UPLOAD_EXPIRED`(409)는 계약 추가가 선행** — Task 6 착수 전에 project-docs PR 머지를 확인한다.
- 정책 초기값(yml 기본): Free `aiQuery MONTHLY 100`·`paperRegistration MONTHLY 3`, Pro `aiQuery MONTHLY 1000`·`paperRegistration MONTHLY 100`. 정리: interval `10m`, 스캔 deadline chat `30m` / upload-pending `1h` / processing `3h`.
- 비용은 `BigDecimal` ↔ `numeric(14,8)`만. float/double 금지.
- `UsageService`의 쓰기 메서드는 `@Transactional(propagation = MANDATORY)` — 항상 호출부 트랜잭션에 합류한다.
- 엔티티는 `@Getter`만, 상태 변경은 의도 드러나는 메서드/repository CAS. 컨텍스트 간 참조는 ID로.
- 코드 주석: 짧게, 티켓 키·spec § 인용 금지.
- 월 경계는 KST(`Asia/Seoul`), 저장은 UTC Instant.
- 테스트: `cd be && ./gradlew test --tests '<클래스>'` (통합 테스트는 Testcontainers — Bash timeout 600000).

---

### Task 1: plan 도메인 기반 — 엔티티·리포지토리·정책 설정·BucketPeriod

**Files:**
- Create: `be/src/main/java/com/ymc/plan/domain/PlanCode.java`, `UsageType.java`, `PolicyMode.java`, `UsageRecordStatus.java`
- Create: `be/src/main/java/com/ymc/plan/domain/PlanEntitlement.java`, `UsageBucket.java`, `UsageRecord.java`
- Create: `be/src/main/java/com/ymc/plan/domain/PlanEntitlementRepository.java`, `UsageBucketRepository.java`, `UsageRecordRepository.java`
- Create: `be/src/main/java/com/ymc/plan/infra/PlanProperties.java`
- Create: `be/src/main/java/com/ymc/plan/service/BucketPeriod.java`
- Modify: `be/src/main/resources/application.yml` (`plan:` 섹션 추가)
- Modify: `be/src/test/java/com/ymc/support/IntegrationTest.java` (`resetState`에 신규 리포지토리 3개 정리 추가)
- Create: `be/docs/db/plan.sql`
- Test: `be/src/test/java/com/ymc/plan/service/BucketPeriodTest.java`, `be/src/test/java/com/ymc/plan/infra/PlanPropertiesTest.java`

**Interfaces (Produces — 이후 모든 task가 사용):**
- enum `PlanCode { FREE, PRO }`, `UsageType { AI_QUERY, PAPER_REGISTRATION }`, `PolicyMode { MONTHLY, UNLIMITED }`, `UsageRecordStatus { RESERVED, CONFIRMED, RELEASED }`
- `BucketPeriod.startOf(Instant): Instant`, `BucketPeriod.nextResetAfter(Instant): Instant` (static)
- `PlanProperties.policyOf(PlanCode, UsageType): Policy` — `Policy(PolicyMode mode, Integer limit)`; `PlanProperties.cleanup(): Cleanup(Duration interval, Duration chatGeneratingDeadline, Duration uploadPendingDeadline, Duration processingDeadline)`
- `PlanEntitlement.grant(UUID userId, PlanCode planCode, Instant startedAt, Instant endedAt, Instant now)` 정적 팩토리
- `UsageBucket.open(UUID userId, UsageType usageType, PlanCode planCode, Instant bucketStart, Instant now)`
- `UsageRecord.reserve(UUID bucketId, UsageType usageType, UUID sourceId, Instant now)`
- Repository 시그니처는 Step 3 코드 참조.

- [ ] **Step 1: 실패하는 단위 테스트 — BucketPeriod**

```java
// be/src/test/java/com/ymc/plan/service/BucketPeriodTest.java
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
```

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'BucketPeriodTest'`
Expected: 컴파일 실패 (BucketPeriod 없음)

- [ ] **Step 3: 구현**

`BucketPeriod`:

```java
package com.ymc.plan.service;

import java.time.Instant;
import java.time.ZoneId;

/** KST 달력월 경계 계산. 예약·조회가 같은 계산을 쓰도록 여기에만 둔다. */
public final class BucketPeriod {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private BucketPeriod() {
    }

    /** at이 속한 KST 달력월 1일 00:00의 UTC 시각. */
    public static Instant startOf(Instant at) {
        return at.atZone(KST).toLocalDate().withDayOfMonth(1).atStartOfDay(KST).toInstant();
    }

    /** 다음 KST 달력월 1일 00:00의 UTC 시각 — 계약 resetAt. */
    public static Instant nextResetAfter(Instant at) {
        return at.atZone(KST).toLocalDate().withDayOfMonth(1).plusMonths(1)
                .atStartOfDay(KST).toInstant();
    }
}
```

enum 4개는 각각 단독 파일, 값은 Interfaces 블록 그대로.

`PlanEntitlement` (관행: `@Getter`, protected 기본 생성자, 정적 팩토리, enum은 `@Enumerated(EnumType.STRING)`):

```java
package com.ymc.plan.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * 기본값(Free)에서 벗어난 플랜 권한의 원본 기록. 유효 기간은 [startedAt, endedAt) —
 * 만료는 시각이 지나면 자동 반영되므로 갱신 작업이 없다.
 */
@Getter
@Entity
@Table(
        name = "plan_entitlement",
        indexes = @Index(
                name = "idx_plan_entitlement_lookup",
                columnList = "user_id, plan_code, started_at, ended_at"))
public class PlanEntitlement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, updatable = false, length = 16)
    private PlanCode planCode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false, updatable = false)
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlanEntitlement() {
        // JPA
    }

    private PlanEntitlement(UUID userId, PlanCode planCode, Instant startedAt, Instant endedAt,
            Instant now) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.planCode = Objects.requireNonNull(planCode, "planCode");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.endedAt = Objects.requireNonNull(endedAt, "endedAt");
        this.createdAt = Objects.requireNonNull(now, "now");
        if (!endedAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("endedAt은 startedAt 이후여야 합니다.");
        }
    }

    public static PlanEntitlement grant(UUID userId, PlanCode planCode, Instant startedAt,
            Instant endedAt, Instant now) {
        return new PlanEntitlement(userId, planCode, startedAt, endedAt, now);
    }
}
```

`UsageBucket` — 같은 관행. 필드 `id`(UUID, BE 생성)·`userId`·`usageType`·`planCode`·`bucketStart`·`createdAt`, `@Table(name = "usage_bucket", uniqueConstraints = @UniqueConstraint(name = "uk_usage_bucket", columnNames = {"user_id", "usage_type", "plan_code", "bucket_start"}))`. 클래스 주석: `/** 월 버킷 — 예약 직렬화의 잠금 앵커. 집계 카운터를 두지 않는다. */`

`UsageRecord` — 필드 `id`·`bucketId`(UUID, FK 어노테이션 없이 ID 참조)·`usageType`·`sourceId`·`status`·`estimatedCostUsd`·`createdAt`·`updatedAt`. 비용 컬럼:

```java
@Column(name = "estimated_cost_usd", precision = 14, scale = 8)
private BigDecimal estimatedCostUsd;
```

`@Table(name = "usage_record", uniqueConstraints = @UniqueConstraint(name = "uk_usage_record_type_source", columnNames = {"usage_type", "source_id"}), indexes = @Index(name = "idx_usage_record_bucket", columnList = "bucket_id"))`. `reserve(...)` 팩토리는 status를 `RESERVED`로, `updatedAt = now`로 초기화. 클래스 주석: `/** 실행별 사용량 원장. 해제해도 RELEASED로 남긴다 — 감사와 중복 신호 판정. */`

리포지토리 3개:

```java
// PlanEntitlementRepository
public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, UUID> {

    /** 유효 권한 존재 판정 — [startedAt, endedAt) 기간 조건. */
    boolean existsByUserIdAndPlanCodeAndStartedAtLessThanEqualAndEndedAtGreaterThan(
            UUID userId, PlanCode planCode, Instant at, Instant sameAt);

    List<PlanEntitlement> findAllByUserIdAndPlanCodeAndStartedAtLessThanEqualAndEndedAtGreaterThan(
            UUID userId, PlanCode planCode, Instant at, Instant sameAt);
}

// UsageBucketRepository
public interface UsageBucketRepository extends JpaRepository<UsageBucket, UUID> {

    /** 버킷 확보 — 있으면 무시. 잠금 전에 행 존재를 보장한다. */
    @Modifying
    @Query(value = """
            insert into usage_bucket (id, user_id, usage_type, plan_code, bucket_start, created_at)
            values (:id, :userId, :usageType, :planCode, :bucketStart, :now)
            on conflict on constraint uk_usage_bucket do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
            @Param("usageType") String usageType, @Param("planCode") String planCode,
            @Param("bucketStart") Instant bucketStart, @Param("now") Instant now);

    /** 예약 직렬화 지점 — SELECT ... FOR UPDATE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from UsageBucket b
             where b.userId = :userId and b.usageType = :usageType
               and b.planCode = :planCode and b.bucketStart = :bucketStart
            """)
    Optional<UsageBucket> findWithLock(@Param("userId") UUID userId,
            @Param("usageType") UsageType usageType, @Param("planCode") PlanCode planCode,
            @Param("bucketStart") Instant bucketStart);

    Optional<UsageBucket> findByUserIdAndUsageTypeAndPlanCodeAndBucketStart(
            UUID userId, UsageType usageType, PlanCode planCode, Instant bucketStart);
}

// UsageRecordRepository
public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    Optional<UsageRecord> findByUsageTypeAndSourceId(UsageType usageType, UUID sourceId);

    long countByBucketIdAndStatusIn(UUID bucketId, Collection<UsageRecordStatus> statuses);

    /** 정산 CAS — RESERVED일 때만 1 row. 중복 신호는 0 row로 걸러진다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UsageRecord r
               set r.status = :to, r.estimatedCostUsd = :cost, r.updatedAt = :now
             where r.usageType = :usageType and r.sourceId = :sourceId
               and r.status = com.ymc.plan.domain.UsageRecordStatus.RESERVED
            """)
    int settleOne(@Param("usageType") UsageType usageType, @Param("sourceId") UUID sourceId,
            @Param("to") UsageRecordStatus to, @Param("cost") BigDecimal cost,
            @Param("now") Instant now);

    /** 문서 종결 정산 — 연결된 Paper 전체를 한 번에. 이미 정산된 row는 조건이 거른다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UsageRecord r
               set r.status = :to, r.updatedAt = :now
             where r.usageType = :usageType and r.sourceId in :sourceIds
               and r.status = com.ymc.plan.domain.UsageRecordStatus.RESERVED
            """)
    int settleAll(@Param("usageType") UsageType usageType,
            @Param("sourceIds") Collection<UUID> sourceIds,
            @Param("to") UsageRecordStatus to, @Param("now") Instant now);
}
```

`PlanProperties` — 등록·테스트 방식은 `chat/infra/ChatStreamProperties.java`·`ChatStreamPropertiesTest.java`를 그대로 따른다:

```java
package com.ymc.plan.infra;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;
import com.ymc.plan.domain.UsageType;

/** 플랜·기능별 사용량 정책과 정리 주기. 값은 배포 설정으로 덮는다. */
@ConfigurationProperties(prefix = "plan")
public record PlanProperties(Map<PlanCode, Map<UsageType, Policy>> policy, Cleanup cleanup) {

    public record Policy(PolicyMode mode, Integer limit) {
        public Policy {
            if (mode == PolicyMode.MONTHLY && (limit == null || limit < 0)) {
                throw new IllegalArgumentException("MONTHLY 정책에는 0 이상의 limit이 필요합니다.");
            }
        }
    }

    public record Cleanup(Duration interval, Duration chatGeneratingDeadline,
            Duration uploadPendingDeadline, Duration processingDeadline) {
    }

    public Policy policyOf(PlanCode plan, UsageType usageType) {
        Policy found = policy.getOrDefault(plan, Map.of()).get(usageType);
        if (found == null) {
            throw new IllegalStateException("정책 누락: " + plan + "/" + usageType);
        }
        return found;
    }
}
```

`application.yml`의 `chat:` 블록 아래에 추가:

```yaml
# FT-011 플랜·사용량 — 값은 베타 초기 정책. 배포 정책값(env)으로 덮는다
plan:
  policy:
    free:
      ai-query: { mode: MONTHLY, limit: 100 }
      paper-registration: { mode: MONTHLY, limit: 3 }
    pro:
      ai-query: { mode: MONTHLY, limit: 1000 }
      paper-registration: { mode: MONTHLY, limit: 100 }
  cleanup:
    interval: 10m                    # 정리 실행 주기
    chat-generating-deadline: 30m    # GENERATING 스캔 — run deadline(10m)+emitter(11m)보다 크게
    upload-pending-deadline: 1h      # UPLOAD_PENDING 스캔 — presigned(10m)보다 크게
    processing-deadline: 3h          # UPLOADED·PROCESSING 스캔 — 워커 deadline(3600s) 기준(dev)
```

`PlanPropertiesTest`는 `ChatStreamPropertiesTest`와 같은 방식으로 기본값 바인딩을 검증한다 — `policyOf(FREE, AI_QUERY)`가 `MONTHLY 100`, `policyOf(PRO, PAPER_REGISTRATION)`이 `MONTHLY 100`, `cleanup().interval()`이 10분.

`IntegrationTest.resetState` 맨 앞에 추가 (`@Autowired protected` 필드 3개도 함께):

```java
usageRecordRepository.deleteAll();
usageBucketRepository.deleteAll();
planEntitlementRepository.deleteAll();
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'BucketPeriodTest' --tests 'PlanPropertiesTest'`
Expected: PASS

- [ ] **Step 5: prod DDL — `be/docs/db/plan.sql` 생성** (기존 `chat.sql` 서식·타입 관행)

```sql
-- FT-011 플랜·사용량. prod 첫 배포 전 실행 (dev는 ddl-auto가 생성).
create table plan_entitlement (
    id          uuid not null,
    user_id     uuid not null,
    -- 현재 PRO만. 유효 기간은 [started_at, ended_at)
    plan_code   varchar(16) not null,
    started_at  timestamp(6) with time zone not null,
    ended_at    timestamp(6) with time zone not null,
    created_at  timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_plan_entitlement_lookup
    on plan_entitlement (user_id, plan_code, started_at, ended_at);

-- 월 버킷 — 예약 직렬화의 잠금 앵커. 집계 카운터 없음
create table usage_bucket (
    id           uuid not null,
    user_id      uuid not null,
    usage_type   varchar(32) not null,
    -- 판정 플랜별 버킷 분리 — 월 중 플랜 변경 시 집계가 섞이지 않는다
    plan_code    varchar(16) not null,
    -- KST 월 1일 00:00의 UTC 시각
    bucket_start timestamp(6) with time zone not null,
    created_at   timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_usage_bucket unique (user_id, usage_type, plan_code, bucket_start)
);

-- 실행별 원장. 해제해도 RELEASED로 남긴다
create table usage_record (
    id                 uuid not null,
    bucket_id          uuid not null,
    usage_type         varchar(32) not null,
    -- AI 질의: clientMessageId, 문서 등록: paperId
    source_id          uuid not null,
    -- RESERVED → CONFIRMED / RELEASED
    status             varchar(16) not null,
    -- AI CONFIRMED에만. run.completed의 추정 비용(USD)
    estimated_cost_usd numeric(14, 8),
    created_at         timestamp(6) with time zone not null,
    updated_at         timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_usage_record_type_source unique (usage_type, source_id)
);
create index idx_usage_record_bucket on usage_record (bucket_id);
```

- [ ] **Step 6: 커밋**

```bash
git add be/src/main/java/com/ymc/plan be/src/main/resources/application.yml \
  be/src/test/java/com/ymc/plan be/src/test/java/com/ymc/support/IntegrationTest.java \
  be/docs/db/plan.sql
git commit -m "[YMC-344] feat(plan): 플랜·사용량 도메인 기반 추가"
```

---

### Task 2: PlanService — 유효 플랜 판정

**Files:**
- Create: `be/src/main/java/com/ymc/plan/service/PlanService.java`
- Test: `be/src/test/java/com/ymc/plan/service/PlanServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `PlanEntitlementRepository`, `PlanCode`.
- Produces: `PlanService.effectivePlan(UUID userId, Instant at): PlanCode`, `PlanService.proExpiresAt(UUID userId, Instant at): Optional<Instant>` — Task 3·9가 사용.

- [ ] **Step 1: 실패하는 테스트**

```java
// be/src/test/java/com/ymc/plan/service/PlanServiceTest.java
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
        Instant started = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant ended = started.plus(30, ChronoUnit.DAYS);
        planEntitlementRepository.save(PlanEntitlement.grant(
                TEST_USER_ID, PlanCode.PRO, started, ended, started));

        assertThat(planService.effectivePlan(TEST_USER_ID, started)).isEqualTo(PlanCode.PRO);
        assertThat(planService.effectivePlan(TEST_USER_ID, ended.minusNanos(1)))
                .isEqualTo(PlanCode.PRO);
        assertThat(planService.effectivePlan(TEST_USER_ID, ended)).isEqualTo(PlanCode.FREE);
        assertThat(planService.proExpiresAt(TEST_USER_ID, started)).contains(ended);
    }

    @Test
    @DisplayName("겹치는 권한이 여럿이면 가장 늦은 만료 시각")
    void latestExpiryWins() {
        Instant now = Instant.now();
        Instant endedShort = now.plus(7, ChronoUnit.DAYS);
        Instant endedLong = now.plus(30, ChronoUnit.DAYS);
        planEntitlementRepository.save(PlanEntitlement.grant(
                TEST_USER_ID, PlanCode.PRO, now.minusSeconds(60), endedShort, now));
        planEntitlementRepository.save(PlanEntitlement.grant(
                TEST_USER_ID, PlanCode.PRO, now.minusSeconds(60), endedLong, now));

        assertThat(planService.proExpiresAt(TEST_USER_ID, now)).contains(endedLong);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'PlanServiceTest'`
Expected: 컴파일 실패 (PlanService 없음)

- [ ] **Step 3: 구현**

```java
package com.ymc.plan.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PlanEntitlement;
import com.ymc.plan.domain.PlanEntitlementRepository;

import lombok.RequiredArgsConstructor;

/**
 * 요청 시각 기준 유효 플랜 판정. 상태를 바꾸지 않으므로 잠금이 없고, 만료는 시각이
 * 지나면 자동 반영된다.
 */
@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanEntitlementRepository entitlementRepository;

    public PlanCode effectivePlan(UUID userId, Instant at) {
        boolean pro = entitlementRepository
                .existsByUserIdAndPlanCodeAndStartedAtLessThanEqualAndEndedAtGreaterThan(
                        userId, PlanCode.PRO, at, at);
        return pro ? PlanCode.PRO : PlanCode.FREE;
    }

    /** 현재 유효한 Pro 권한 중 가장 늦은 만료 시각. 없으면 empty. */
    public Optional<Instant> proExpiresAt(UUID userId, Instant at) {
        return entitlementRepository
                .findAllByUserIdAndPlanCodeAndStartedAtLessThanEqualAndEndedAtGreaterThan(
                        userId, PlanCode.PRO, at, at)
                .stream()
                .map(PlanEntitlement::getEndedAt)
                .max(Instant::compareTo);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'PlanServiceTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/plan/service/PlanService.java \
  be/src/test/java/com/ymc/plan/service/PlanServiceTest.java
git commit -m "[YMC-344] feat(plan): 유효 플랜 판정 서비스"
```

---

### Task 3: UsageService.reserve — 원자적 예약과 429

**Files:**
- Modify: `be/src/main/java/com/ymc/common/error/ErrorCode.java` (2개 추가)
- Create: `be/src/main/java/com/ymc/plan/service/UsageService.java` (reserve만; confirm·release는 Task 4)
- Test: `be/src/test/java/com/ymc/plan/service/UsageServiceTest.java`

**Interfaces:**
- Consumes: Task 1 전체, Task 2 `PlanService.effectivePlan`.
- Produces: `UsageService.reserve(UUID userId, UsageType usageType, UUID sourceId): void` — MANDATORY 트랜잭션, 한도 초과 시 `ApiException`(AI_QUERY → `CHAT_USAGE_LIMIT_EXCEEDED`, PAPER_REGISTRATION → `PAPER_USAGE_LIMIT_EXCEEDED`). Task 5·6·7이 사용.
- `ErrorCode.CHAT_USAGE_LIMIT_EXCEEDED`, `ErrorCode.PAPER_USAGE_LIMIT_EXCEEDED` (둘 다 `HttpStatus.TOO_MANY_REQUESTS`).

- [ ] **Step 1: 실패하는 테스트**

`UsageServiceTest extends IntegrationTest`. reserve는 MANDATORY라 `tx.execute(s -> ...)`로 감싼다. 테스트 케이스 (기본 정책값 그대로 사용 — paper 한도 3이 작아서 적합):

```java
// be/src/test/java/com/ymc/plan/service/UsageServiceTest.java
package com.ymc.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'UsageServiceTest'`
Expected: 컴파일 실패 (UsageService·ErrorCode 값 없음)

- [ ] **Step 3: 구현**

`ErrorCode`에 채팅 코드들 아래 추가:

```java
/** 채팅 월간 사용량 한도 초과 (FT-011) */
CHAT_USAGE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),

/** 문서 등록 월간 사용량 한도 초과 (FT-011) */
PAPER_USAGE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
```

`UsageService`:

```java
package com.ymc.plan.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;
import com.ymc.plan.domain.UsageBucket;
import com.ymc.plan.domain.UsageBucketRepository;
import com.ymc.plan.domain.UsageRecord;
import com.ymc.plan.domain.UsageRecordRepository;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.infra.PlanProperties;

import lombok.RequiredArgsConstructor;

/**
 * 사용량 예약·확정·해제. 모든 쓰기가 호출부의 시작·종결 트랜잭션에 합류해야 하므로
 * MANDATORY다 — 단독 호출은 설계 위반이고 즉시 예외로 드러난다.
 */
@Service
@RequiredArgsConstructor
public class UsageService {

    private static final List<UsageRecordStatus> ACTIVE =
            List.of(UsageRecordStatus.RESERVED, UsageRecordStatus.CONFIRMED);

    private final PlanService planService;
    private final PlanProperties properties;
    private final UsageBucketRepository bucketRepository;
    private final UsageRecordRepository recordRepository;

    /**
     * 한도 판정과 1회 예약을 원자적으로 처리한다. 유한 정책은 버킷 행 잠금으로 동시
     * 예약을 직렬화하고, UNLIMITED는 잠금 없이 기록만 남긴다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID userId, UsageType usageType, UUID sourceId) {
        Instant now = Instant.now();
        Optional<UsageRecord> existing =
                recordRepository.findByUsageTypeAndSourceId(usageType, sourceId);
        if (existing.isPresent()) {
            if (existing.get().getStatus() == UsageRecordStatus.RELEASED) {
                throw new IllegalStateException(
                        "해제된 실행의 재예약: " + usageType + "/" + sourceId);
            }
            return; // 같은 실행의 재전달 — 이미 예약·확정됨
        }
        PlanCode plan = planService.effectivePlan(userId, now);
        PlanProperties.Policy policy = properties.policyOf(plan, usageType);
        Instant bucketStart = BucketPeriod.startOf(now);
        bucketRepository.insertIfAbsent(UUID.randomUUID(), userId,
                usageType.name(), plan.name(), bucketStart, now);

        UsageBucket bucket;
        if (policy.mode() == PolicyMode.MONTHLY) {
            bucket = bucketRepository
                    .findWithLock(userId, usageType, plan, bucketStart).orElseThrow();
            long used = recordRepository.countByBucketIdAndStatusIn(bucket.getId(), ACTIVE);
            if (used >= policy.limit()) {
                throw limitExceeded(usageType);
            }
        } else {
            bucket = bucketRepository
                    .findByUserIdAndUsageTypeAndPlanCodeAndBucketStart(
                            userId, usageType, plan, bucketStart).orElseThrow();
        }
        recordRepository.save(UsageRecord.reserve(bucket.getId(), usageType, sourceId, now));
    }

    private ApiException limitExceeded(UsageType usageType) {
        return switch (usageType) {
            case AI_QUERY -> new ApiException(ErrorCode.CHAT_USAGE_LIMIT_EXCEEDED,
                    "이번 달 AI 질의 횟수를 모두 사용했습니다.");
            case PAPER_REGISTRATION -> new ApiException(ErrorCode.PAPER_USAGE_LIMIT_EXCEEDED,
                    "이번 달 문서 등록 횟수를 모두 사용했습니다.");
        };
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'UsageServiceTest'`
Expected: PASS (동시성 테스트 포함)

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/common/error/ErrorCode.java \
  be/src/main/java/com/ymc/plan/service/UsageService.java \
  be/src/test/java/com/ymc/plan/service/UsageServiceTest.java
git commit -m "[YMC-344] feat(plan): 버킷 잠금 예약과 사용량 한도 429"
```

---

### Task 4: UsageService.confirm / release — 정산 CAS와 0행 구분

**Files:**
- Modify: `be/src/main/java/com/ymc/plan/service/UsageService.java`
- Test: `be/src/test/java/com/ymc/plan/service/UsageServiceTest.java` (추가)

**Interfaces:**
- Produces (Task 5·6·7·8이 사용):
  - `confirm(UsageType usageType, UUID sourceId, BigDecimal estimatedCostUsd): void`
  - `release(UsageType usageType, UUID sourceId): void`
  - `confirmAll(UsageType usageType, List<UUID> sourceIds): void`
  - `releaseAll(UsageType usageType, List<UUID> sourceIds): void`
  - 전부 MANDATORY.

- [ ] **Step 1: 실패하는 테스트 추가** (`UsageServiceTest`에)

```java
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
```

(import 추가: `java.math.BigDecimal`)

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'UsageServiceTest'`
Expected: 컴파일 실패 (confirm·release 없음)

- [ ] **Step 3: 구현** (`UsageService`에 추가; `org.slf4j.Logger` 정적 필드 `log` 추가)

```java
@Transactional(propagation = Propagation.MANDATORY)
public void confirm(UsageType usageType, UUID sourceId, BigDecimal estimatedCostUsd) {
    settle(usageType, sourceId, UsageRecordStatus.CONFIRMED, estimatedCostUsd);
}

@Transactional(propagation = Propagation.MANDATORY)
public void release(UsageType usageType, UUID sourceId) {
    settle(usageType, sourceId, UsageRecordStatus.RELEASED, null);
}

@Transactional(propagation = Propagation.MANDATORY)
public void confirmAll(UsageType usageType, List<UUID> sourceIds) {
    if (!sourceIds.isEmpty()) {
        recordRepository.settleAll(usageType, sourceIds,
                UsageRecordStatus.CONFIRMED, Instant.now());
    }
}

@Transactional(propagation = Propagation.MANDATORY)
public void releaseAll(UsageType usageType, List<UUID> sourceIds) {
    if (!sourceIds.isEmpty()) {
        recordRepository.settleAll(usageType, sourceIds,
                UsageRecordStatus.RELEASED, Instant.now());
    }
}

private void settle(UsageType usageType, UUID sourceId, UsageRecordStatus to,
        BigDecimal cost) {
    if (recordRepository.settleOne(usageType, sourceId, to, cost, Instant.now()) == 1) {
        return;
    }
    if (recordRepository.findByUsageTypeAndSourceId(usageType, sourceId).isPresent()) {
        log.debug("이미 정산된 실행의 중복 신호 무시: {}/{}", usageType, sourceId);
    } else {
        // 배포 전에 시작된 실행이면 정상. 그 외에는 예약 훅 누락 신호다
        log.warn("예약 없는 정산 신호: {}/{}/{}", usageType, sourceId, to);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'UsageServiceTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/plan/service/UsageService.java \
  be/src/test/java/com/ymc/plan/service/UsageServiceTest.java
git commit -m "[YMC-344] feat(plan): 정산 CAS와 중복 신호 구분"
```

---

### Task 5: 채팅 훅 — 예약·확정·비용 전달

**Files:**
- Modify: `be/src/main/java/com/ymc/chat/service/port/AiStreamListener.java` (`onRunCompleted` 시그니처)
- Modify: `be/src/main/java/com/ymc/chat/infra/ai/AiAgentWebClientAdapter.java` (비용 파싱)
- Modify: `be/src/main/java/com/ymc/chat/infra/ai/FakeAiAgentStreamAdapter.java`
- Modify: `be/src/main/java/com/ymc/chat/service/ChatMessageTransitions.java`
- Modify: `be/src/main/java/com/ymc/chat/service/ChatStreamService.java` (Run의 complete·fail 호출부)
- Modify: `be/src/main/java/com/ymc/chat/service/ChatCommandService.java` (start에 reserve)
- Test: `be/src/test/java/com/ymc/chat/api/ChatUsageIntegrationTest.java` (신규) + 기존 `transitions.complete/fail` 호출 테스트들 시그니처 갱신

**Interfaces:**
- Consumes: Task 3·4의 `UsageService.reserve/confirm/release`, `UsageType.AI_QUERY`.
- Produces: `AiStreamListener.onRunCompleted(BigDecimal estimatedCostUsd)`; `ChatMessageTransitions.complete(UUID messageId, String content, UUID clientMessageId, BigDecimal estimatedCostUsd): boolean`, `fail(UUID messageId, UUID clientMessageId): boolean` — Task 8이 fail을 사용.

- [ ] **Step 1: 실패하는 테스트** (신규 클래스; fake 스트림은 `IntegrationTest`가 `ai.fake-stream=true`로 켠다)

```java
// be/src/test/java/com/ymc/chat/api/ChatUsageIntegrationTest.java
package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatMessageTransitions;
import com.ymc.chat.service.ChatStartResult;
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
        documentTransitions.markParsed(document.getId(), DocumentStatus.COMPLETED, null);
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

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/papers/{paperId}/chat/messages/stream", paper.getId())
                        .contentType("application/json")
                        .content("""
                                {"clientMessageId":"%s","content":"질문"}"""
                                .formatted(UUID.randomUUID()))
                        .with(userJwt()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("CHAT_USAGE_LIMIT_EXCEEDED"));
        assertThat(chatMessageRepository.count()).isZero();
    }
}
```

스트림 endpoint 경로·본문 필드는 `ChatMessageStreamIntegrationTest`의 실제 요청과 대조해 맞춘다 (path·필드명이 다르면 그쪽이 정답).

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'ChatUsageIntegrationTest'`
Expected: 컴파일 실패 (complete 4-인자 없음)

- [ ] **Step 3: 구현**

1. `AiStreamListener.onRunCompleted()` → `onRunCompleted(BigDecimal estimatedCostUsd)`. Javadoc: `/** run 성공 종결. estimatedCostUsd는 추정 비용(USD) — 없으면 null. */`
2. `AiAgentWebClientAdapter.dispatch`의 `case "run.completed"`:

```java
case "run.completed" -> {
    terminalSeen.set(true);
    listener.onRunCompleted(optionalCost(event.data()));
}
```

같은 클래스에 추가 — 비용 파싱 실패는 스트림을 죽이지 않는다(바깥 try의 rethrow를 타면 안 되므로 내부에서 삼킨다):

```java
/** run.completed의 선택 필드. 없거나 숫자가 아니면 null — 성공 처리를 막지 않는다. */
private BigDecimal optionalCost(String data) {
    try {
        JsonNode node = objectMapper.readTree(data).get("estimated_cost_usd");
        return node != null && node.isNumber() ? node.decimalValue() : null;
    } catch (JsonProcessingException e) {
        log.warn("estimated_cost_usd 파싱 실패 — 비용 없이 진행", e);
        return null;
    }
}
```

3. `FakeAiAgentStreamAdapter`의 `listener.onRunCompleted();` → `listener.onRunCompleted(new BigDecimal("0.001"));`
4. `ChatMessageTransitions` — `UsageService` 주입(`UsageType.AI_QUERY`):

```java
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
```

5. `ChatStreamService`: `onRunCompleted(BigDecimal estimatedCostUsd)`로 시그니처 변경, `transitions.complete(ids.assistantMessageId(), finalContent, ids.clientMessageId(), estimatedCostUsd)`. `transitions.fail(...)` 호출부 전부(`failLocked`·`failWith` 안)를 `fail(ids.assistantMessageId(), ids.clientMessageId())`로. `grep -rn 'transitions\.\(complete\|fail\)' be/src`로 호출부를 전수 확인한다.
6. `ChatCommandService.start` — `UsageService` 주입, `CHAT_RUN_IN_PROGRESS` 검사 직후·row 저장 전에:

```java
usageService.reserve(ownerId, UsageType.AI_QUERY, clientMessageId);
```

7. 기존 테스트의 `transitions.complete(id, "답변")`·`fail(id)` 호출부를 새 시그니처로 갱신 — `ChatSessionHistoryIntegrationTest.givenCompletedExchange` 등은 `started.clientMessageId()`를 넘기고 비용은 `null`.

- [ ] **Step 4: 통과 확인 (채팅 스위트 전체)**

Run: `cd be && ./gradlew test --tests 'com.ymc.chat.*'`
Expected: PASS — 기존 스트림·세션 테스트 포함

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/chat be/src/test/java/com/ymc/chat
git commit -m "[YMC-344] feat(chat): 질의 예약·확정 훅과 비용 전달"
```

---

### Task 6: paper.expired_at — 만료 표시와 파생·거절

**선행 확인: openapi에 `UPLOAD_EXPIRED`가 머지됐는지 확인한다** (`grep UPLOAD_EXPIRED ../project-docs/contracts/frontend-backend/openapi.yaml`). 없으면 BLOCKED로 보고.

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/Paper.java` (`expiredAt` 필드)
- Modify: `be/src/main/java/com/ymc/paper/domain/PaperRepository.java` (`markExpired`, `deleteExpiredByOwnerAndFilename`)
- Modify: `be/src/main/java/com/ymc/paper/service/PaperDocumentViews.java` (파생 규칙)
- Modify: `be/src/main/java/com/ymc/paper/service/PaperUploadCompletionService.java` (만료 거절)
- Modify: `be/src/main/java/com/ymc/common/error/ErrorCode.java` (`UPLOAD_EXPIRED(HttpStatus.CONFLICT)`)
- Modify: `be/docs/db/paper.sql` (`expired_at` 컬럼)
- Test: `be/src/test/java/com/ymc/paper/api/PaperExpiryIntegrationTest.java` (신규)

**Interfaces:**
- Produces: `Paper.getExpiredAt(): Instant`, `PaperRepository.markExpired(UUID paperId, Instant now): int`(CAS), `PaperRepository.deleteExpiredByOwnerAndFilename(UUID ownerId, String filename): int` — Task 7·8이 사용.

- [ ] **Step 1: 실패하는 테스트**

```java
// be/src/test/java/com/ymc/paper/api/PaperExpiryIntegrationTest.java
package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PaperExpiryIntegrationTest extends IntegrationTest {

    private Paper givenExpiredPaper(String filename) {
        Paper paper = givenPendingPaper(filename);
        tx.executeWithoutResult(s ->
                paperRepository.markExpired(paper.getId(), Instant.now()));
        return reload(paper.getId());
    }

    @Test
    @DisplayName("만료된 Paper의 상태는 EXPIRED로 파생된다")
    void expiredStatusDerived() throws Exception {
        Paper paper = givenExpiredPaper("expired.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("document가 연결된 Paper는 만료 CAS가 0행이다")
    void expireCasSkipsLinkedPaper() {
        Paper paper = givenPendingPaper("linked.pdf");
        givenLinkedDocument(paper);

        Integer updated = tx.execute(s ->
                paperRepository.markExpired(paper.getId(), Instant.now()));

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("만료된 Paper의 complete는 409 UPLOAD_EXPIRED")
    void completeRejectedAfterExpiry() throws Exception {
        Paper paper = givenExpiredPaper("expired.pdf");
        givenUploadedObject(paper);

        mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_EXPIRED"));
    }

    @Test
    @DisplayName("같은 파일명 재등록이 만료 row를 대체한다")
    void reregisterReplacesExpiredRow() throws Exception {
        Paper expired = givenExpiredPaper("retry.pdf");

        mockMvc.perform(post("/api/papers")
                        .contentType("application/json")
                        .content(createPaperJson("retry.pdf"))
                        .with(userJwt()))
                .andExpect(status().isCreated());

        assertThat(paperRepository.findById(expired.getId())).isEmpty();
        assertThat(paperRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 안 된 같은 파일명은 여전히 DUPLICATE_FILENAME")
    void nonExpiredDuplicateStillRejected() throws Exception {
        givenPendingPaper("dup.pdf");

        mockMvc.perform(post("/api/papers")
                        .contentType("application/json")
                        .content(createPaperJson("dup.pdf"))
                        .with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FILENAME"));
    }
}
```

status 조회 endpoint 경로는 기존 paper 통합 테스트와 대조해 맞춘다. 재등록 테스트 2건은 Task 7의 register 변경까지 있어야 통과한다 — 이 task에서는 나머지 3건 통과가 목표고, 재등록 2건은 `@org.junit.jupiter.api.Disabled("Task 7에서 활성화")`로 잠시 막고 커밋한다.

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'PaperExpiryIntegrationTest'`
Expected: 컴파일 실패 (markExpired 없음)

- [ ] **Step 3: 구현**

`Paper`에 필드 추가 (`lastAccessedAt` 아래):

```java
/** 정리 스케줄러가 채우는 만료 시각. 정상 경로에서는 항상 null이다. */
@Column(name = "expired_at")
private Instant expiredAt;
```

`PaperRepository`에 추가:

```java
/** 만료 CAS — 업로드 미완(document 미연결)이고 아직 만료 전일 때만 1 row. */
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        update Paper p
           set p.expiredAt = :now, p.updatedAt = :now
         where p.id = :paperId
           and p.documentId is null
           and p.expiredAt is null
        """)
int markExpired(@Param("paperId") UUID paperId, @Param("now") Instant now);

/** 만료 잔재 제거 — 같은 파일명 재등록이 만료 row를 대체할 수 있게 한다. */
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        delete from Paper p
         where p.ownerId = :ownerId and p.filename = :filename
           and p.expiredAt is not null
        """)
int deleteExpiredByOwnerAndFilename(@Param("ownerId") UUID ownerId,
        @Param("filename") String filename);
```

`PaperDocumentViews.derivedStatus` 맨 앞에:

```java
if (paper.getExpiredAt() != null) {
    return PaperStatus.EXPIRED;
}
```

`PaperUploadCompletionService.complete` — 소유자 검증 직후에:

```java
if (paper.getExpiredAt() != null) {
    // 만료가 먼저 커밋됐으면 예약은 이미 반환됐다 — 업로드를 이어가지 않는다
    throw new ApiException(ErrorCode.UPLOAD_EXPIRED, "업로드가 만료된 논문입니다. 다시 등록해 주세요.");
}
```

`ErrorCode`에 `UPLOAD_CHECKSUM_MISSING` 아래:

```java
/** 만료 처리된 논문의 complete — 재등록 필요 (FT-011) */
UPLOAD_EXPIRED(HttpStatus.CONFLICT),
```

`be/docs/db/paper.sql`의 `paper` 테이블에 (기존 서식대로):

```sql
-- 정리 스케줄러의 만료 시각 (null = 만료 아님). 값이 있으면 상태가 EXPIRED로 파생된다
expired_at       timestamp(6) with time zone,
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'PaperExpiryIntegrationTest' --tests 'com.ymc.paper.*'`
Expected: 재등록 2건(Disabled) 제외 전부 PASS, 기존 paper 테스트 회귀 없음

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/paper be/src/main/java/com/ymc/common/error/ErrorCode.java \
  be/src/test/java/com/ymc/paper/api/PaperExpiryIntegrationTest.java be/docs/db/paper.sql
git commit -m "[YMC-344] feat(paper): expired_at 만료 표시와 EXPIRED 파생"
```

---

### Task 7: 문서 훅 — 등록 예약, 재사용·종결 정산

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/PaperRegistrationService.java`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperDocumentLinkService.java`
- Modify: `be/src/main/java/com/ymc/paper/service/DocumentTransitions.java` (`markParsedAndSettle`)
- Modify: `be/src/main/java/com/ymc/paper/service/ParseResultService.java`
- Modify: `be/src/main/java/com/ymc/paper/domain/DocumentRepository.java` (`findWithLockByChecksumSha256`)
- Modify: `be/src/main/java/com/ymc/paper/domain/PaperRepository.java` (`findIdsByDocumentId`)
- Test: `be/src/test/java/com/ymc/paper/api/PaperUsageIntegrationTest.java` (신규), `PaperExpiryIntegrationTest`의 Disabled 2건 활성화

**Interfaces:**
- Consumes: Task 3·4 `UsageService`, Task 6 `deleteExpiredByOwnerAndFilename`.
- Produces: `DocumentTransitions.markParsedAndSettle(UUID documentId, DocumentStatus terminal, String errorCode): boolean` — Task 8이 사용.

- [ ] **Step 1: 실패하는 테스트**

```java
// be/src/test/java/com/ymc/paper/api/PaperUsageIntegrationTest.java
package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.DocumentTransitions;
import com.ymc.paper.service.PaperDocumentLinkService;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

class PaperUsageIntegrationTest extends IntegrationTest {

    @Autowired
    PaperDocumentLinkService linkService;

    @Autowired
    UsageService usageService;

    private UsageRecordStatus recordStatusOf(UUID paperId) {
        return usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.PAPER_REGISTRATION, paperId)
                .orElseThrow().getStatus();
    }

    private Paper givenReservedPendingPaper(String filename) {
        Paper paper = givenPendingPaper(filename);
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, paper.getId()));
        return paper;
    }

    @Test
    @DisplayName("등록은 RESERVED를 남기고, 한도(월 3회) 초과는 429 — Paper·URL 미생성")
    void registerReservesAndRejectsAtLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/papers")
                            .contentType("application/json")
                            .content(createPaperJson("p" + i + ".pdf"))
                            .with(userJwt()))
                    .andExpect(status().isCreated());
        }
        assertThat(usageRecordRepository.count()).isEqualTo(3);

        mockMvc.perform(post("/api/papers")
                        .contentType("application/json")
                        .content(createPaperJson("p4.pdf"))
                        .with(userJwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PAPER_USAGE_LIMIT_EXCEEDED"));
        assertThat(paperRepository.count()).isEqualTo(3);
        assertThat(usageRecordRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("COMPLETED document 재사용 연결은 즉시 confirm")
    void reuseCompletedConfirms() {
        Paper first = givenReservedPendingPaper("origin.pdf");
        Document document = givenLinkedDocument(first);
        documentTransitions.markProcessing(document.getId());
        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.COMPLETED, null));
        assertThat(recordStatusOf(first.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "reuse.pdf", Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                OTHER_USER_ID, UsageType.PAPER_REGISTRATION, second.getId()));
        tx.executeWithoutResult(s -> linkService.linkOrCreate(
                second.getId(), second.getFileKey(), document.getChecksumSha256()));

        assertThat(recordStatusOf(second.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    @Test
    @DisplayName("FAILED document 재사용 연결은 즉시 release")
    void reuseFailedReleases() {
        Paper first = givenReservedPendingPaper("origin.pdf");
        Document document = givenLinkedDocument(first);
        documentTransitions.markProcessing(document.getId());
        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.FAILED, "PARSE_FAILED"));
        assertThat(recordStatusOf(first.getId())).isEqualTo(UsageRecordStatus.RELEASED);

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "reuse.pdf", Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                OTHER_USER_ID, UsageType.PAPER_REGISTRATION, second.getId()));
        tx.executeWithoutResult(s -> linkService.linkOrCreate(
                second.getId(), second.getFileKey(), document.getChecksumSha256()));

        assertThat(recordStatusOf(second.getId())).isEqualTo(UsageRecordStatus.RELEASED);
    }

    @Test
    @DisplayName("파싱 종결이 연결된 모든 Paper를 한 번에 정산한다")
    void terminalSettlesAllLinkedPapers() {
        Paper first = givenReservedPendingPaper("a.pdf");
        Document document = givenLinkedDocument(first);
        // 파싱 중 같은 파일을 올린 두 번째 사용자
        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "b.pdf", Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                OTHER_USER_ID, UsageType.PAPER_REGISTRATION, second.getId()));
        tx.executeWithoutResult(s ->
                paperRepository.linkDocument(second.getId(), document.getId(), Instant.now()));
        documentTransitions.markProcessing(document.getId());

        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.COMPLETED, null));

        assertThat(recordStatusOf(first.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
        assertThat(recordStatusOf(second.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }

    @Test
    @DisplayName("중복 종결 신호는 정산을 다시 만들지 않는다")
    void duplicateTerminalIsNoop() {
        Paper paper = givenReservedPendingPaper("a.pdf");
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.COMPLETED, null));

        Boolean second = tx.execute(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.FAILED, "LATE"));

        assertThat(second).isFalse();
        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }
}
```

`Document.getChecksumSha256()` 접근자 이름은 실제 엔티티와 대조한다.

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'PaperUsageIntegrationTest'`
Expected: 컴파일 실패 (markParsedAndSettle 없음)

- [ ] **Step 3: 구현**

1. `PaperRegistrationService.register` — `UsageService` 주입. "3. 사전 조회" 블록을:

```java
// 3. 사전 조회 — 만료 잔재는 새 등록이 대체하므로 먼저 지운다
paperRepository.deleteExpiredByOwnerAndFilename(ownerId, filename);
if (paperRepository.existsByOwnerIdAndFilename(ownerId, filename)) {
    throw duplicateFilename(filename);
}

Paper paper = Paper.register(ownerId, filename, Instant.now());
usageService.reserve(ownerId, UsageType.PAPER_REGISTRATION, paper.getId());
```

2. `DocumentRepository`에 추가:

```java
/** 연결·종결 직렬화용 잠금 조회 — 종결 UPDATE와 이 잠금이 같은 행에서 만난다. */
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select d from Document d where d.checksumSha256 = :checksumSha256")
Optional<Document> findWithLockByChecksumSha256(@Param("checksumSha256") String checksumSha256);
```

3. `PaperDocumentLinkService.linkOrCreate` — `UsageService` 주입, 조회를 잠금 조회로 바꾸고 terminal 정산 추가:

```java
@Transactional
public LinkOutcome linkOrCreate(UUID paperId, String fileKey, String checksumSha256) {
    Instant now = Instant.now();
    boolean created = documentRepository.insertIfAbsent(
            UUID.randomUUID(), checksumSha256, fileKey, paperId, now) == 1;
    // 잠금 조회 — 종결 전이와 직렬화해 "연결 직후 종결"의 정산 누락을 막는다
    Document document = documentRepository.findWithLockByChecksumSha256(checksumSha256)
            .orElseThrow(() -> new IllegalStateException(
                    "생성 직후 조회에 실패한 document: paperId=" + paperId));
    paperRepository.linkDocument(paperId, document.getId(), now);
    if (document.getStatus() == DocumentStatus.COMPLETED) {
        usageService.confirm(UsageType.PAPER_REGISTRATION, paperId, null);
    } else if (document.getStatus() == DocumentStatus.FAILED) {
        usageService.release(UsageType.PAPER_REGISTRATION, paperId);
    }
    return new LinkOutcome(document, !created);
}
```

4. `DocumentTransitions` — `PaperRepository`·`UsageService` 주입, 추가:

```java
/**
 * 결과 수신 전이 + 연결된 모든 Paper의 사용량 정산을 한 트랜잭션으로. 전이 주인일 때만
 * 정산한다 — 쪼개면 전이만 커밋되고 정산이 유실될 수 있다.
 */
@Transactional
public boolean markParsedAndSettle(UUID documentId, DocumentStatus terminal, String errorCode) {
    boolean owner = documentRepository.markParsed(documentId, terminal, errorCode,
            Instant.now()) == 1;
    if (!owner) {
        return false;
    }
    List<UUID> paperIds = paperRepository.findIdsByDocumentId(documentId);
    if (terminal == DocumentStatus.COMPLETED) {
        usageService.confirmAll(UsageType.PAPER_REGISTRATION, paperIds);
    } else {
        usageService.releaseAll(UsageType.PAPER_REGISTRATION, paperIds);
    }
    return true;
}
```

`markParsed`는 다른 호출자가 없으면 제거하고 호출부를 확인한다 (`grep -rn 'markParsed(' be/src` — 테스트 픽스처가 쓰면 `markParsedAndSettle`로 교체).

5. `PaperRepository`에:

```java
@Query("select p.id from Paper p where p.documentId = :documentId")
List<UUID> findIdsByDocumentId(@Param("documentId") UUID documentId);
```

6. `ParseResultService.apply` — `transitions.markParsed(...)` 호출을 `transitions.markParsedAndSettle(...)`로 교체.
7. `PaperExpiryIntegrationTest`의 `@Disabled` 2건 활성화.

- [ ] **Step 4: 통과 확인 (paper 스위트 전체 — 파싱 파이프라인 회귀 포함)**

Run: `cd be && ./gradlew test --tests 'com.ymc.paper.*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/paper be/src/test/java/com/ymc/paper
git commit -m "[YMC-344] feat(paper): 등록 예약과 재사용·종결 정산"
```

---

### Task 8: 정체 레코드 정리 스케줄러

**Files:**
- Create: `be/src/main/java/com/ymc/common/config/SchedulingConfig.java` (`@EnableScheduling`)
- Create: `be/src/main/java/com/ymc/chat/service/StaleChatCleanup.java`
- Create: `be/src/main/java/com/ymc/paper/service/StalePaperCleanup.java`
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatMessageRepository.java` (스캔 쿼리)
- Modify: `be/src/main/java/com/ymc/paper/domain/PaperRepository.java`, `DocumentRepository.java` (스캔 쿼리)
- Test: `be/src/test/java/com/ymc/chat/service/StaleChatCleanupTest.java`, `be/src/test/java/com/ymc/paper/service/StalePaperCleanupTest.java`

**Interfaces:**
- Consumes: Task 5 `ChatMessageTransitions.fail(messageId, clientMessageId)`, Task 6 `markExpired`, Task 7 `markParsedAndSettle`, Task 4 `release`, Task 1 `PlanProperties.cleanup()`.

- [ ] **Step 1: 실패하는 테스트**

```java
// be/src/test/java/com/ymc/chat/service/StaleChatCleanupTest.java
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
                TEST_USER_ID, UsageType.AI_QUERY, clientMessageId));
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
```

`ChatSession.open`·`ChatMessage.userMessage/assistantGenerating`의 실제 시그니처(paperId 인자 등)는 엔티티 파일과 대조해 맞춘다.

```java
// be/src/test/java/com/ymc/paper/service/StalePaperCleanupTest.java
package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

class StalePaperCleanupTest extends IntegrationTest {

    @Autowired
    StalePaperCleanup cleanup;

    @Autowired
    UsageService usageService;

    private Paper givenReservedPaperAt(String filename, Instant createdAt) {
        Paper paper = paperRepository.save(Paper.register(TEST_USER_ID, filename, createdAt));
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, paper.getId()));
        return paper;
    }

    private UsageRecordStatus recordStatusOf(UUID paperId) {
        return usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.PAPER_REGISTRATION, paperId)
                .orElseThrow().getStatus();
    }

    @Test
    @DisplayName("오래된 UPLOAD_PENDING은 EXPIRED + 해제, 최근 것은 남긴다")
    void expiresStalePending() {
        Paper stale = givenReservedPaperAt("stale.pdf", Instant.now().minus(2, ChronoUnit.HOURS));
        Paper fresh = givenReservedPaperAt("fresh.pdf", Instant.now());

        cleanup.run();

        assertThat(reload(stale.getId()).getExpiredAt()).isNotNull();
        assertThat(recordStatusOf(stale.getId())).isEqualTo(UsageRecordStatus.RELEASED);
        assertThat(reload(fresh.getId()).getExpiredAt()).isNull();
        assertThat(recordStatusOf(fresh.getId())).isEqualTo(UsageRecordStatus.RESERVED);
    }

    @Test
    @DisplayName("오래 정체된 PROCESSING document는 FAILED + 연결 Paper 해제")
    void failsStaleProcessing() {
        Paper paper = givenReservedPaperAt("proc.pdf", Instant.now());
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        // 전이 시각을 과거로 — 스캔 기준(updated_at)을 넘긴다
        tx.executeWithoutResult(s -> documentRepository.backdateUpdatedAt(
                document.getId(), Instant.now().minus(4, ChronoUnit.HOURS)));

        cleanup.run();

        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.RELEASED);
    }
}
```

`backdateUpdatedAt`는 테스트를 위해 `DocumentRepository`에 추가하는 `@Modifying @Query("update Document d set d.updatedAt = :at where d.id = :id")`다.

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'StaleChatCleanupTest' --tests 'StalePaperCleanupTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`SchedulingConfig`:

```java
package com.ymc.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 스케줄러 스레드는 기본 1개 — 정리 작업들이 순차 실행된다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

`ChatMessageRepository`에 스캔 쿼리:

```java
List<ChatMessage> findAllByRoleAndStatusAndCreatedAtBefore(
        ChatMessageRole role, ChatMessageStatus status, Instant cutoff);
```

`StaleChatCleanup`:

```java
package com.ymc.chat.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.plan.infra.PlanProperties;

import lombok.RequiredArgsConstructor;

/**
 * 서버가 죽어 종결 전이가 남지 않은 GENERATING 정체를 회수한다. 전이가 CAS라 살아있는
 * relay와 경쟁해도 한쪽만 이기고, 중복 실행도 무해하다.
 */
@Component
@RequiredArgsConstructor
public class StaleChatCleanup {

    private static final Logger log = LoggerFactory.getLogger(StaleChatCleanup.class);

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageTransitions transitions;
    private final PlanProperties properties;

    @Scheduled(fixedDelayString = "${plan.cleanup.interval}")
    public void run() {
        Instant cutoff = Instant.now().minus(properties.cleanup().chatGeneratingDeadline());
        List<ChatMessage> stale = chatMessageRepository
                .findAllByRoleAndStatusAndCreatedAtBefore(
                        ChatMessageRole.ASSISTANT, ChatMessageStatus.GENERATING, cutoff);
        for (ChatMessage message : stale) {
            if (transitions.fail(message.getId(), message.getClientMessageId())) {
                log.info("정체 GENERATING 정리: messageId={}", message.getId());
            }
        }
    }
}
```

`PaperRepository`·`DocumentRepository` 스캔 쿼리:

```java
// PaperRepository
@Query("""
        select p.id from Paper p
         where p.documentId is null and p.expiredAt is null and p.createdAt < :cutoff
        """)
List<UUID> findStaleUploadPendingIds(@Param("cutoff") Instant cutoff);

// DocumentRepository
@Query("select d.id from Document d where d.status in :statuses and d.updatedAt < :cutoff")
List<UUID> findStaleIds(@Param("statuses") Collection<DocumentStatus> statuses,
        @Param("cutoff") Instant cutoff);
```

`StalePaperCleanup` — 항목별 처리는 트랜잭션 빈에 위임한다. `markExpired`+release는 한 트랜잭션이어야 하므로 작은 전이 메서드를 함께 만든다:

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.infra.PlanProperties;
import com.ymc.plan.service.UsageService;

import lombok.RequiredArgsConstructor;

/** 업로드·파싱의 정체 레코드를 회수한다. 항목별 CAS라 중복 실행이 무해하다. */
@Component
@RequiredArgsConstructor
public class StalePaperCleanup {

    private static final Logger log = LoggerFactory.getLogger(StalePaperCleanup.class);

    private final PaperRepository paperRepository;
    private final DocumentRepository documentRepository;
    private final DocumentTransitions documentTransitions;
    private final UsageService usageService;
    private final PlanProperties properties;
    private final TransactionTemplate tx;

    @Scheduled(fixedDelayString = "${plan.cleanup.interval}")
    public void run() {
        Instant now = Instant.now();
        expireStalePending(now.minus(properties.cleanup().uploadPendingDeadline()));
        failStaleDocuments(now.minus(properties.cleanup().processingDeadline()));
    }

    private void expireStalePending(Instant cutoff) {
        for (UUID paperId : paperRepository.findStaleUploadPendingIds(cutoff)) {
            tx.executeWithoutResult(s -> {
                if (paperRepository.markExpired(paperId, Instant.now()) == 1) {
                    usageService.release(UsageType.PAPER_REGISTRATION, paperId);
                    log.info("정체 UPLOAD_PENDING 만료: paperId={}", paperId);
                }
            });
        }
    }

    private void failStaleDocuments(Instant cutoff) {
        List<UUID> staleIds = documentRepository.findStaleIds(
                List.of(DocumentStatus.UPLOADED, DocumentStatus.PROCESSING), cutoff);
        for (UUID documentId : staleIds) {
            if (documentTransitions.markParsedAndSettle(
                    documentId, DocumentStatus.FAILED, "STALE_CLEANUP")) {
                log.info("정체 document 실패 처리: documentId={}", documentId);
            }
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'StaleChatCleanupTest' --tests 'StalePaperCleanupTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/common/config/SchedulingConfig.java \
  be/src/main/java/com/ymc/chat be/src/main/java/com/ymc/paper \
  be/src/test/java/com/ymc/chat/service/StaleChatCleanupTest.java \
  be/src/test/java/com/ymc/paper/service/StalePaperCleanupTest.java
git commit -m "[YMC-344] feat: 정체 레코드 정리 스케줄러"
```

---

### Task 9: GET /api/me/plan

**Files:**
- Create: `be/src/main/java/com/ymc/plan/service/PlanQueryService.java`
- Create: `be/src/main/java/com/ymc/plan/api/PlanController.java`
- Create: `be/src/main/java/com/ymc/plan/api/dto/PlanUsageResponse.java`
- Test: `be/src/test/java/com/ymc/plan/api/PlanApiIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1·2·4 산출물 전체.
- 응답은 계약 `PlanUsageResponse`/`PlanFeatureUsage`/`UsageLimit` 그대로: `{plan, planExpiresAt, usage:{aiQuery:{mode,limit,used,remaining,resetAt}, paperRegistration:{...}}}` — UNLIMITED면 네 필드 null.

- [ ] **Step 1: 실패하는 테스트**

```java
// be/src/test/java/com/ymc/plan/api/PlanApiIntegrationTest.java
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
            usageService.reserve(TEST_USER_ID, UsageType.AI_QUERY, confirmed);
            usageService.reserve(TEST_USER_ID, UsageType.AI_QUERY, UUID.randomUUID());
        });
        tx.executeWithoutResult(s -> usageService.confirm(UsageType.AI_QUERY, confirmed, null));

        mockMvc.perform(get("/api/me/plan").with(userJwt()))
                .andExpect(jsonPath("$.usage.aiQuery.used").value(2))
                .andExpect(jsonPath("$.usage.aiQuery.remaining").value(98));
    }

    @Test
    @DisplayName("유효한 Pro는 플랜·만료 시각·Pro 한도를 반환한다")
    void proPlanWithExpiry() throws Exception {
        Instant now = Instant.now();
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
```

`planExpiresAt` 직렬화 형식(`ended.toString()`)이 실제 응답과 다르면 기존 API의 Instant 직렬화 관행에 맞춘다.

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'PlanApiIntegrationTest'`
Expected: 404 또는 컴파일 실패

- [ ] **Step 3: 구현**

`PlanUsageResponse` (record, 계약 필드명 그대로):

```java
package com.ymc.plan.api.dto;

import java.time.Instant;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;

public record PlanUsageResponse(PlanCode plan, Instant planExpiresAt, PlanFeatureUsage usage) {

    public record PlanFeatureUsage(UsageLimit aiQuery, UsageLimit paperRegistration) {
    }

    /** MONTHLY는 네 필드 모두 채우고, UNLIMITED는 mode 외 전부 null (계약 UsageLimit). */
    public record UsageLimit(PolicyMode mode, Integer limit, Long used, Long remaining,
            Instant resetAt) {

        public static UsageLimit unlimited() {
            return new UsageLimit(PolicyMode.UNLIMITED, null, null, null, null);
        }
    }
}
```

`PlanQueryService`:

```java
package com.ymc.plan.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.plan.api.dto.PlanUsageResponse;
import com.ymc.plan.api.dto.PlanUsageResponse.PlanFeatureUsage;
import com.ymc.plan.api.dto.PlanUsageResponse.UsageLimit;
import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;
import com.ymc.plan.domain.UsageBucketRepository;
import com.ymc.plan.domain.UsageRecordRepository;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.infra.PlanProperties;

import lombok.RequiredArgsConstructor;

/** 현재 플랜·사용량 조회. 읽기만 하며 버킷을 만들지 않는다 — 없으면 0회. */
@Service
@RequiredArgsConstructor
public class PlanQueryService {

    private static final List<UsageRecordStatus> ACTIVE =
            List.of(UsageRecordStatus.RESERVED, UsageRecordStatus.CONFIRMED);

    private final PlanService planService;
    private final PlanProperties properties;
    private final UsageBucketRepository bucketRepository;
    private final UsageRecordRepository recordRepository;

    @Transactional(readOnly = true)
    public PlanUsageResponse myPlan(UUID userId) {
        Instant now = Instant.now();
        PlanCode plan = planService.effectivePlan(userId, now);
        Instant expiresAt = plan == PlanCode.PRO
                ? planService.proExpiresAt(userId, now).orElse(null) : null;
        return new PlanUsageResponse(plan, expiresAt, new PlanFeatureUsage(
                usageOf(userId, plan, UsageType.AI_QUERY, now),
                usageOf(userId, plan, UsageType.PAPER_REGISTRATION, now)));
    }

    private UsageLimit usageOf(UUID userId, PlanCode plan, UsageType usageType, Instant now) {
        PlanProperties.Policy policy = properties.policyOf(plan, usageType);
        if (policy.mode() == PolicyMode.UNLIMITED) {
            return UsageLimit.unlimited();
        }
        long used = bucketRepository
                .findByUserIdAndUsageTypeAndPlanCodeAndBucketStart(
                        userId, usageType, plan, BucketPeriod.startOf(now))
                .map(b -> recordRepository.countByBucketIdAndStatusIn(b.getId(), ACTIVE))
                .orElse(0L);
        return new UsageLimit(PolicyMode.MONTHLY, policy.limit(), used,
                Math.max(0, policy.limit() - used), BucketPeriod.nextResetAfter(now));
    }
}
```

`PlanController` — 인증 principal 추출은 `ChatController`의 방식을 그대로 따른다:

```java
package com.ymc.plan.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ymc.plan.api.dto.PlanUsageResponse;
import com.ymc.plan.service.PlanQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/me/plan")
@RequiredArgsConstructor
public class PlanController {

    private final PlanQueryService planQueryService;

    /** 계약 getMyPlan — 현재 플랜과 기능별 사용량. */
    @GetMapping
    public PlanUsageResponse myPlan(@AuthenticationPrincipal Jwt jwt) {
        return planQueryService.myPlan(UUID.fromString(jwt.getSubject()));
    }
}
```

시큐리티 설정이 경로별 허용 목록이면 `/api/me/**` 인증 필수 등록을 확인한다 (`common/config` 또는 `user/infra/security`의 SecurityFilterChain).

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'PlanApiIntegrationTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/plan be/src/test/java/com/ymc/plan
git commit -m "[YMC-344] feat(plan): 플랜·사용량 조회 API"
```

---

### Task 10: 운영 준비물과 마무리

**Files:**
- Create: `be/docs/db/plan-beta-pro.sql`
- Test: 전체 스위트

- [ ] **Step 1: 베타 Pro 부여 DML 기록**

```sql
-- 베타 테스터 Pro 부여. :user_id를 치환해 실행한다.
-- KST 부여 시각부터 달력상 1개월 — PG interval이 말일을 보정한다 (1/31 + 1 month = 2/28).
insert into plan_entitlement (id, user_id, plan_code, started_at, ended_at, created_at)
values (
    gen_random_uuid(),
    :user_id,
    'PRO',
    now(),
    ((now() at time zone 'Asia/Seoul') + interval '1 month') at time zone 'Asia/Seoul',
    now()
);
```

- [ ] **Step 2: 전체 테스트**

Run: `cd be && ./gradlew test`
Expected: PASS. 실패가 있으면 원인 테스트를 spec 기준으로 고쳐 이 커밋에 포함.

- [ ] **Step 3: 커밋**

```bash
git add be/docs/db/plan-beta-pro.sql
git commit -m "[YMC-344] docs(db): 베타 Pro 부여 DML"
```

- [ ] **Step 4: 푸시·PR** (본문 양식: 배경·변경사항·검증. 컨트롤러가 최종 검토 후 생성)

```bash
git push -u origin YMC-344-plan-usage-impl
gh pr create --title "[YMC-344] 플랜·사용량 제한 BE 구현" --body "## 배경

FT-011 BE 구현. 설계는 docs/superpowers/specs/2026-08-28-plan-usage-limit-design.md, 예약 의미론은 ADR-006.

## 변경사항

- plan 도메인 신설: plan_entitlement·usage_bucket·usage_record, 버킷 행 잠금 예약, 정산 CAS.
- chat: start 예약, 종결 전이에서 확정·해제, run.completed의 estimated_cost_usd 저장.
- paper: 등록 예약, 재사용·종결 정산(연결 잠금으로 정산 누락 방지), expired_at 만료 표시와 재등록 대체.
- 정체 레코드 정리 스케줄러(YMC-351): GENERATING 30m / UPLOAD_PENDING 1h / UPLOADED·PROCESSING 3h, 주기 10m.
- GET /api/me/plan, 429 코드 2종, UPLOAD_EXPIRED.
- **판단** — UsageService 쓰기는 MANDATORY 전파: 정산이 항상 전이 트랜잭션에 합류함을 타입이 아닌 런타임에서 강제.
- prod DDL: be/docs/db/plan.sql·paper.sql(expired_at). 베타 Pro DML: plan-beta-pro.sql. 정책값 env 주입은 YMC-349.

## 검증

- 신규 통합 테스트: 동시 예약 한도, 정산 멱등, 채팅·문서 훅, 재사용/FAILED 연결 정산, 만료·재등록 대체, 스케줄러, /api/me/plan. 전체 ./gradlew test 통과.
- 미검증: dev 실배포에서의 스케줄러 동작(배포 후 로그 확인), 정책값 env 덮어쓰기(YMC-349에서).

## 의존

- 선행 머지됨: YMC-355(세션 논리 삭제), project-docs UPLOAD_EXPIRED 계약."
```
