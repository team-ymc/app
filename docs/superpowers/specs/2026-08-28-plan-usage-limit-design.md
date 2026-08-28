# 플랜·사용량 제한 — BE 구현 스펙

- 날짜: 2026-08-28
- 범위: BE(`app/be`) — 플랜 판정 + 사용량 예약·확정·해제 + `/api/me/plan` + 정체 레코드 정리.
  FE(한도 초과 안내 UI)는 UI 확정 후 별도 spec. AI·인프라 변경 없음(정책값 env는 YMC-349).
- 계약 SSOT: `project-docs/contracts/frontend-backend/openapi.yaml` (`/api/me/plan`,
  `CHAT_USAGE_LIMIT_EXCEEDED`·`PAPER_USAGE_LIMIT_EXCEEDED`),
  `project-docs/contracts/backend-ai/sse/simple-agent-run-stream.yml`
  (`run.completed.estimated_cost_usd` — project-docs PR #39 머지 대기)
- 결정 근거: ADR-006(Accepted), FT-011, `geunhh/paperTeacher/plan-usage/effective-plan-resolution.md`,
  `project-docs/architecture/runtime-timeouts.md` §5(스캔 deadline 확정, 2026-08-28)
- 티켓: YMC-344(본체), YMC-351(정체 레코드 정리), YMC-349(정책값 주입, 인프라)
- 선행 의존: YMC-355(세션 논리 삭제 전환) — 물리 삭제는 생성 중 `GENERATING` row를 지워
  예약이 영구 누수된다(§5). 관련 후속: YMC-354(FAILED Document 재등록 재파싱 검토)
- 2026-08-28 Codex 리뷰 반영: `paper.expired_at`, FAILED 연결 정산, 스캔 키 `created_at`,
  EXPIRED 파일명 재등록 대체, 정책 적용 시점·cutover·0행 구분 명시

## 1. 목표와 불변식

- 유한 정책에서 동시 요청이 한도를 초과하지 않는다 — 한도 판정과 예약이 버킷 행 잠금으로 직렬화된다.
- 성공 완료만 사용량으로 확정되고, 실패·만료·정체는 예약이 해제된다. 전이와 정산은 항상 같은
  트랜잭션이다 — "전이됐는데 정산이 안 된" 상태가 없다.
- 같은 실행(sourceId)은 한 번만 예약·확정·해제된다. 중복 신호는 조건부 UPDATE가 0행으로 거른다.
- 요청 시작 시 판정한 플랜은 실행 종료까지 유지된다 — 판정 결과가 원장 row(버킷의 plan_code)에
  남아 confirm이 재판정하지 않는다.
- Pro로 판정된 실행은 Free 사용량을 소비하지 않는다 — 버킷이 플랜별로 분리된다.

## 2. 도메인 구조

신규 패키지 `com.ymc.plan`:

```
plan/
  api/          PlanController (/api/me/plan), dto
  domain/       PlanEntitlement, UsageBucket, UsageRecord (+ Repository)
  service/      PlanService(유효 플랜 판정), UsageService(reserve/confirm/release),
                PlanQueryService(조회), BucketPeriod(KST 월 경계 유틸)
```

- 의존 방향은 chat·paper → plan 단방향. chat·paper 서비스가 `UsageService`를 직접 주입받아
  호출한다. port 인터페이스는 두지 않는다 — 기존 port는 외부 인프라(AI·S3·SQS) 경계 전용.
- 정체 레코드 정리 스케줄러는 각 도메인에 둔다: `chat`에 채팅 정리, `paper`에 문서 정리 컴포넌트.
  상태 전이는 도메인 소유이고, plan에 두면 plan → chat·paper 역방향 의존이 생긴다.
- `@EnableScheduling` 신규 도입. 스케줄러 스레드는 Spring 기본 1개 공유 — 두 컴포넌트가
  순차 실행되며, 정리 작업은 짧아 문제 없다.

## 3. 저장 모델

dev는 ddl-auto가 생성, prod용 수기 DDL은 `be/docs/db/plan.sql`. prod 첫 배포 전 반영 필수.

### `plan_entitlement` — Pro 권한 기록

| 컬럼 | 타입·제약 | 설명 |
|---|---|---|
| `id` | uuid PK | BE 생성 |
| `user_id` | uuid not null, FK → users | |
| `plan_code` | varchar(16) not null | 현재 `PRO`만. 판정 쿼리·인덱스가 이 컬럼을 조건으로 쓴다 |
| `started_at` / `ended_at` | timestamptz not null | `[started_at, ended_at)` — 종료 시각 미포함 |
| `created_at` | timestamptz not null | |

인덱스 `(user_id, plan_code, started_at, ended_at)`. Free는 기록하지 않는다 — 유효한 PRO 권한이
없으면 Free (판정은 EXISTS 하나, effective-plan-resolution.md). 만료 갱신 작업이 없다.

### `usage_bucket` — 월 버킷 (잠금 앵커)

| 컬럼 | 타입·제약 | 설명 |
|---|---|---|
| `id` | uuid PK | |
| `user_id` | uuid not null | |
| `usage_type` | varchar(32) not null | `AI_QUERY` / `PAPER_REGISTRATION` |
| `plan_code` | varchar(16) not null | `FREE` / `PRO` — 판정 플랜별 버킷 분리 |
| `bucket_start` | timestamptz not null | KST 월 1일 00:00의 UTC 시각 (`BucketPeriod` 산출) |
| `created_at` | timestamptz not null | |

유니크 `(user_id, usage_type, plan_code, bucket_start)`. 집계 카운터 없음(ADR-006).
plan_code를 키에 넣는 이유: 월 중 플랜이 바뀌어도 집계가 섞이지 않는다 — "같은 버킷의 원장을
집계한다"는 ADR 문구가 그대로 성립하고, 판정 스냅샷이 버킷 소속으로 남는다.

### `usage_record` — 실행별 원장

| 컬럼 | 타입·제약 | 설명 |
|---|---|---|
| `id` | uuid PK | |
| `bucket_id` | uuid not null, FK → usage_bucket, index | |
| `usage_type` | varchar(32) not null | 유니크 제약용 (ADR-006 그대로) |
| `source_id` | uuid not null | AI: `clientMessageId`, 문서: `paperId` |
| `status` | varchar(16) not null | `RESERVED → CONFIRMED / RELEASED` |
| `estimated_cost_usd` | numeric(14,8) null | AI `CONFIRMED`에만. `run.completed` 값을 BigDecimal로 파싱 |
| `created_at` / `updated_at` | timestamptz not null | updated_at = 확정·해제 시각 |

- 유니크 `(usage_type, source_id)`. 해제해도 row는 `RELEASED`로 남긴다 — 감사 이력과
  "이미 해제됨" 멱등 판정. 계약상 실패 재시도는 새 clientMessageId·새 paperId라 재예약과
  충돌하지 않는다 (openapi: "FAILED: 새 clientMessageId로 재시도").
- 사용량 집계 = 버킷의 `RESERVED + CONFIRMED` 카운트. `RELEASED` 제외.
- 실패한 run의 비용은 저장하지 않는다 (계약 backend_handling).

### `paper` 변경 — `expired_at`

`expired_at timestamptz null` 컬럼 추가 (`be/docs/db/paper.sql` 갱신). 정상 경로에서는
끝까지 null이고 정리 스케줄러만 채운다. 상태 파생 규칙 맨 앞에 한 줄 추가:

```text
paper.expired_at != null   → EXPIRED          ← 추가
paper.document_id == null  → UPLOAD_PENDING
paper.document_id != null  → document.status 그대로
```

- `complete`는 초입에서 `expired_at != null`이면 거절한다 — 만료가 먼저 커밋됐으면 예약이
  이미 반환됐으므로 업로드를 이어가지 않는다.
- **EXPIRED 파일명 재등록 대체**: `register`의 파일명 검사에서 EXPIRED row는 중복으로 치지
  않는다. 같은 파일명의 EXPIRED row가 있으면 같은 트랜잭션에서 삭제 후 새로 등록한다 —
  삭제 API가 없어 만료 항목이 재등록을 영구히 막는 것을 방지. 옛 paperId의 원장은
  `RELEASED`로 남는다(FK 없음).

## 4. 예약·확정·해제 메커니즘

### reserve — 한 트랜잭션 4단계

```sql
INSERT INTO usage_bucket (...) VALUES (...) ON CONFLICT DO NOTHING;      -- ① 버킷 확보
SELECT id FROM usage_bucket WHERE ... FOR UPDATE;                        -- ② 잠금 (직렬화 지점)
SELECT COUNT(*) FROM usage_record
  WHERE bucket_id=? AND status IN ('RESERVED','CONFIRMED');              -- ③ 집계
INSERT INTO usage_record (..., status='RESERVED');                       -- ④ 한도 미만이면 삽입
```

- ③이 한도 이상이면 usage_type별 429 예외: `AI_QUERY → CHAT_USAGE_LIMIT_EXCEEDED`,
  `PAPER_REGISTRATION → PAPER_USAGE_LIMIT_EXCEEDED`. `ErrorCode`에 두 값 추가
  (`TOO_MANY_REQUESTS`) — 계약에 이미 있으므로 D8 충족. 호출부 변환 코드 없이
  `GlobalExceptionHandler`가 그대로 429로 내보낸다.
- `UNLIMITED` 모드는 ②·③ 생략, ①·④만 — 잠금 없이 기록만 남긴다. FT가 지원 모드로
  명시하므로 분기는 유지하되, 베타 정책값(아래 §8)으로는 모든 조합이 MONTHLY라 미사용.
- 같은 sourceId 재예약은 ④의 유니크가 막는다. 기존 row가 RESERVED·CONFIRMED면 중복 신호로
  무시, RELEASED면 거부.

### confirm / release — 조건부 UPDATE 하나

```sql
UPDATE usage_record SET status=?, estimated_cost_usd=?, updated_at=now()
WHERE usage_type=? AND source_id=? AND status='RESERVED';
```

잠금 불필요(한도 검사 없음 — 버킷 행을 건드리지 않아 예약과 잠금이 얽히지 않는다).
항상 호출부의 전이 트랜잭션에 합류한다(§5).

0행이면 원장 존재를 확인해 구분한다: row가 있으면 중복 신호(debug), 없으면 예약 없는
완료 신호(warn) — 예약 훅 누락의 신호일 수 있다. 단, 배포 cutover 직후에는 배포 전에
시작된 실행이 예약 없이 완료되므로 정상적으로 발생한다(§9).

## 5. 훅 위치

### 채팅 (sourceId = clientMessageId)

| 시점 | 위치 | 내용 |
|---|---|---|
| 예약 | `ChatCommandService.start` | `CHAT_RUN_IN_PROGRESS` 검사 후, user·assistant row 저장 직전. 한도 초과면 트랜잭션 롤백 — row도 message.started도 없다 |
| 확정 | `ChatMessageTransitions.complete` | `markCompleted` CAS 주인일 때만 같은 트랜잭션에서 confirm + 비용 저장. 시그니처에 clientMessageId·cost 추가 |
| 해제 | `ChatMessageTransitions.fail` | CAS 주인일 때만 같은 트랜잭션에서 release. 시그니처에 clientMessageId 추가 |

- 모든 실패 경로(run.failed·idle timeout·deadline·protocol error)가 이미 `fail()`로 수렴하므로
  해제 훅은 한 곳이다. BE가 죽어 fail조차 못 부른 경우만 정리 스케줄러 몫(§7).
- start의 유니크 위반 경로(`DataIntegrityViolationException`)에서 예약 insert도 같이 롤백된다.
- **선행 의존(YMC-355)**: 현재 `deleteSession`은 물리 삭제라, 생성 중 세션을 지우면
  `GENERATING` row가 사라져 완료·실패 CAS도 정리 스캔도 닿지 않는 예약 누수가 생긴다.
  계약·FT-011이 확정한 논리 삭제 전환이 먼저다.
- `estimated_cost_usd`는 `Run`이 run.completed payload에서 BigDecimal로 파싱해 complete에
  전달한다. 필드 부재·파싱 실패는 null로 저장하고 경고 로그 — 확정을 막지 않는다.

### 문서 (sourceId = paperId)

| 시점 | 위치 | 내용 |
|---|---|---|
| 예약 | `PaperRegistrationService.register` | 타입·크기·파일명 검사 후 `saveAndFlush` 직전. 초과면 Paper·presigned URL 없음 |
| 정산 (재사용) | `PaperDocumentLinkService.linkOrCreate` | 기존 document가 terminal이면 연결 트랜잭션에서 정산: `COMPLETED` → confirm, `FAILED` → release |
| 확정·해제 (종결) | `ParseResultService.apply`의 종결 전이 | `markParsed` CAS 주인일 때, 같은 트랜잭션에서 연결된 모든 Paper 정산 |
| EXPIRED | 정리 스케줄러 (§7) | |

FAILED 연결 release가 없으면 그 Paper의 예약은 영구 `RESERVED`로 샌다 — 정리 스케줄러는
비종결 상태만 스캔한다. 실패한 파일의 재등록을 재파싱으로 살리는 개선은 YMC-354.

종결 정산은 paper별 루프 없이 UPDATE 하나:

```sql
UPDATE usage_record SET status='CONFIRMED', updated_at=now()   -- FAILED면 'RELEASED'
WHERE usage_type='PAPER_REGISTRATION'
  AND source_id IN (SELECT id FROM paper WHERE document_id=?)
  AND status='RESERVED';
```

- 파싱 중 연결된 다른 사용자의 Paper도 같이 정산된다. 이미 확정된 옛 Paper는
  `status='RESERVED'` 조건이 0행으로 거른다.
- **경계 레이스 방지**: `linkOrCreate`가 기존 document에 연결할 때 document 행을
  `SELECT ... FOR UPDATE`로 잠근다. 연결과 종결 전이가 직렬화되어, 연결이 먼저면 정산
  서브쿼리에 잡히고 종결이 먼저면 연결 쪽이 COMPLETED를 보고 confirm한다. 잠금이 없으면
  종결 순간에 연결 중이던 Paper의 예약이 RESERVED로 영영 샌다 — 정리 스케줄러는 비종결
  상태만 보므로 회수 못 한다.
- **구조 변경**: `DocumentTransitions.markParsed`가 자체 @Transactional인데, 전이만 커밋되고
  정산 전에 죽으면 같은 누수가 생긴다. 전이+정산을 한 @Transactional 메서드로 묶는다
  (`ChatMessageTransitions`와 같은 모양).

## 6. /api/me/plan

`PlanController → PlanQueryService`(읽기 전용, 잠금 없음):

1. 유효 플랜 판정(EXISTS). PRO면 `planExpiresAt` = 현재 유효 권한 중 가장 늦은 `ended_at`,
   FREE면 null.
2. 기능별 정책값에서 mode. `MONTHLY`: 현재 KST 월 버킷 조회 — 없으면 used 0, **버킷을
   만들지 않는다**(계약 명시). `used` = RESERVED+CONFIRMED 카운트, `remaining` = max(0, limit−used),
   `resetAt` = 다음 KST 월 1일 00:00. `UNLIMITED`: 네 필드 null.
3. `BucketPeriod`가 bucket_start·resetAt 계산을 독점한다 — 예약과 조회가 같은 계산을 쓴다.

## 7. 정체 레코드 정리 (YMC-351)

값은 `runtime-timeouts.md` §5 (2026-08-28 확정): 실행 주기 10m, 스캔 deadline은
채팅 `GENERATING` 30m / `UPLOAD_PENDING` 1h / `UPLOADED`·`PROCESSING` 3h.
스캔이 실행 상한(채팅 deadline 10m+emitter 11m, presigned 10m, 워커 3600s)보다 커야
정상 실행을 오판하지 않는다. 실측 후 상한과 함께 축소한다(SSOT §6-12).
3h는 dev(maxReceiveCount 1) 기준 — prod 전환 시 SQS 재시도 총 수명
(`maxReceiveCount × 워커 deadline`) 기준으로 재계산한다.

| 컴포넌트 | 대상 | 처리 |
|---|---|---|
| chat `StaleChatCleanup` | `created_at`이 deadline 지난 `GENERATING` assistant | `markFailed` CAS + 같은 트랜잭션 release — 기존 fail 경로 재사용. `GENERATING`은 생성 후 종결까지 안 바뀌므로 `created_at`이 실행 시작 시각이다 |
| paper `StalePaperCleanup` | `created_at`이 deadline 지난 `UPLOAD_PENDING`(document 미연결·미만료) Paper | `expired_at` 채움(`WHERE document_id IS NULL AND expired_at IS NULL` CAS) + release. S3 객체 유무 무관 |
| 〃 | 전이 시각이 deadline 지난 `UPLOADED`·`PROCESSING` document | `FAILED` 전이 + 연결 Paper 전체 release (§5 종결 정산과 같은 트랜잭션) |

- 전부 CAS + 조건부 UPDATE라 멱등 — 살아있는 relay·워커 결과와 경쟁해도 한쪽만 이기고,
  중복 실행·다중 인스턴스에서 무해하다. 분산 잠금(ShedLock류) 불필요.
- 스캔 deadline·주기는 §8 정책값으로 주입한다.

## 8. 정책값 주입

`@ConfigurationProperties` 바인딩, 기본값은 application.yml. dev·prod는 ECS env로 덮는다
(YMC-349, 인프라). ECS는 ignore_changes=task_definition이라 env 변경은 apply 후 CD 재배포
필요.

```yaml
plan:
  policy:
    free:
      ai-query: { mode: MONTHLY, limit: 100 }
      paper-registration: { mode: MONTHLY, limit: 3 }
    pro:
      ai-query: { mode: MONTHLY, limit: 1000 }
      paper-registration: { mode: MONTHLY, limit: 100 }
  cleanup:
    interval: 10m
    chat-generating-deadline: 30m
    upload-pending-deadline: 1h
    processing-deadline: 3h
```

Pro 값은 2026-08-28 결정(UNLIMITED → 유한). FT-011 표·Resolved 반영됨(project-docs 커밋 대기).

정책값 변경은 재배포 후 다음 요청부터 적용된다. 집계는 항상 현재 정책의 limit과 비교하므로
같은 달의 기존 기록에 소급된다 — 한도를 낮추면 이미 사용분이 새 한도를 넘어 남은 횟수가
0이 될 수 있고, `UNLIMITED → MONTHLY` 전환 시 UNLIMITED 시절 기록도 같은 버킷이라 집계에
포함된다. 사용자별 예외를 두지 않는다.

## 9. 운영 준비물

- `be/docs/db/plan.sql`: 테이블 3개 + 인덱스 수기 DDL. `paper.sql`에 `expired_at` 반영.
  prod 첫 배포 전 반영 필수 (기존 paper.sql DDL과 같은 대기열).
- 베타 Pro 부여 DML: `plan_entitlement`에 `('PRO', now(), now() + KST 달력 1개월)` insert.
  말일 보정은 실행 시점 계산. 스크립트는 `be/docs/db/`에 기록.
- 배포 cutover: 배포 이전 실행은 backfill하지 않는다 — 사용량은 배포 후 0부터 시작.
  배포 순간 진행 중이던 실행(`GENERATING`·`PROCESSING`)은 예약이 없으므로 완료돼도
  confirm 0행(warn)으로 끝나며 무해하다(§4).

## 10. 테스트

- 예약 동시성: 같은 버킷 동시 reserve N개가 한도를 넘지 않는다 (스레드 경쟁 통합 테스트).
- 멱등: 중복 완료·실패 신호, 같은 sourceId 재예약, RELEASED 후 재예약 거부.
- 다중 Paper 정산: document 종결 시 연결 Paper 전체 confirm/release, 기확정 Paper no-op.
- 연결·종결 경쟁: linkOrCreate 잠금으로 정산 누락이 없다.
- FAILED document 재사용 연결 → release. COMPLETED 재사용 연결 → confirm.
- EXPIRED: 만료 후 complete 거절, 같은 파일명 재등록이 EXPIRED row를 대체,
  만료 CAS와 complete 연결의 경쟁.
- 원장 부재: 예약 없는 완료 신호가 warn으로 남고 확정을 만들지 않는다.
- 정리 스케줄러: deadline 경계값, 살아있는 실행과의 CAS 경쟁, 중복 실행 무해.
- `BucketPeriod`: KST 월 경계(12월→1월), resetAt, 베타 DML 말일 보정.
- 429 응답 코드·플랜 판정 경계(`started_at <= t < ended_at`), 조회가 버킷을 만들지 않음.
- 정책값 변경: 한도 하향 시 남은 횟수 0 처리, `UNLIMITED → MONTHLY` 전환 집계.

## 11. 남는 것

- **선행**: YMC-355 세션 논리 삭제 전환 — 이 spec 구현 착수 전 필요(§5).
- FE 한도 초과 안내·플랜 표시 — UI 확정 후 별도 spec.
- `estimated_cost_usd` 계약(project-docs PR #39) 머지 후 구현 착수. AI 서버의 필드 송신은 AI 담당.
- 실행 상한 실측 → 스캔 deadline 축소 (runtime-timeouts.md §6-12). prod 전환 시
  `UPLOADED`·`PROCESSING` 스캔을 SQS 재시도 총 수명 기준으로 재계산(§7).
- FAILED Document 재등록의 재파싱 경로 — YMC-354.
- 비용 기준 한도 전환은 예약 의미론 재설계가 필요(사후 차감 모델) — 별도 ADR.
