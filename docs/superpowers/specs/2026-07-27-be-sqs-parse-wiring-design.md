# BE ↔ AI 파싱 배선 — BE 측 설계 (fileKey 전달·상태 전이 CAS)

- 날짜: 2026-07-27 (2026-07-28 코드 대조 리뷰 + Codex 재리뷰 반영 — C8 대상 파일 정정, C9·테스트 2건 추가,
  결과 소비 ack 정책 §3 기록, `result` object 타입 강제는 §5로 유예)
- 범위: YMC-276 (BE — fileKey 형식 변경, 상태 전이 CAS 전환)
- 원본: 워크스페이스 루트 `DESIGN-be-ai-sqs-parse-wiring.md` (BE·AI·계약·인프라 통합 설계서, 로컬 문서).
  이 문서는 그중 BE 몫을 repo 관점으로 재구성한 것이다. 구현 착수 시 이 문서가 brainstorming/planning의 입력이다.
- 계약 SSOT: `project-docs/contracts/backend-ai/messaging.yml` (SQS 메시지 payload).
  채널 토폴로지·at-least-once 의미론은 ADR-002.
- 결정 근거: ADR-001, ADR-002, ADR-003(Proposed)
- 관련 티켓: YMC-229(AI 워커) · YMC-266(계약) · YMC-277(인프라)

## 1. 문제 — BE 관점

파싱 경로가 실제로는 연결돼 있지 않다. BE는 `parse-requests`에 `{paperId, fileKey}`를 발행하지만
AI는 `fileKey`를 읽지 않고 `paper_id`로 입력 키를 조립한다(`papers/{paperId}/original.pdf`).
AI 워커(YMC-229)가 메시지의 `fileKey`를 그대로 사용하도록 바뀌므로, BE는 다음 두 가지를 맞춘다.

1. **신규 fileKey 형식** — ownerId를 S3 key에서 제거한다.
2. **상태 전이 경합 방어** — 실제 워커가 붙으면 빠른 결과가 `markProcessing()`보다 먼저
   도착할 수 있다. 현재 load-modify-save 전이는 이 경합에서 깨진다.

메시지 필드는 `{paperId, fileKey}` 그대로이고 DB 컬럼 변경도 없다.

### 역할 구분 (통합 설계서 결정 B)

```text
paperId = 이 파싱 작업과 Paper를 연결하는 식별자 (상관관계·도메인 식별자)
fileKey = 원본 PDF가 저장된 불투명한 S3 객체 키 (형식은 BE 저장소 내부 구현)
```

`fileKey` 형식은 계약이 아니다. 구형 키(`uploads/{ownerId}/{paperId}.pdf`)와 신형 키가 섞여도
BE가 저장된 값을 발행하고 AI가 그대로 읽으면 배선은 유지된다. 기존 row가 있다면
마이그레이션 없이 저장된 `paper.file_key`를 그대로 발행한다.

## 2. 변경 목록

현재 구현 상태는 2026-07-27 기준.

| # | 대상 | 현재 | 변경 |
|---|---|---|---|
| C1 | `paper/domain/Paper.java` — `FILE_KEY_FORMAT` | `"uploads/%s/%s.pdf".formatted(ownerId, id)` | `uploads/{paperId}/original.pdf` — ownerId 제거 |
| C2 | `paper/domain/PaperRepository.java` — `markParsed` | `WHERE status = PROCESSING` CAS | `WHERE status IN (UPLOADED, PROCESSING)` |
| C3 | `paper/domain/PaperRepository.java` | 없음 | `UPLOADED → PROCESSING` CAS 쿼리 신규 (`markUploaded`·`markParsed`와 같은 bulk update 패턴) |
| C4 | `paper/service/PaperTransitions.java` — `markProcessing` | `findById` → 엔티티 `markProcessing()` load-modify-save | CAS 호출로 교체. 0 row면 재조회해 3분기(§3) |
| C5 | `paper/domain/Paper.java` — 엔티티 `markProcessing(Instant)` | `status != UPLOADED`면 `IllegalStateException` ("경쟁 없음" 전제) | 전제가 무효가 되므로 C4와 함께 제거(또는 CAS로 대체됨을 명시) |
| C6 | `paper/service/PaperUploadCompletionService.java` | complete 흐름: `UPLOAD_PENDING` 검증 → `markUploaded` CAS → 발행 → `markProcessing` | 흐름 유지. 빠른 결과 선도착이 실패 로그로 남지 않도록 로그·주석 수정 |
| C7 | `infra/messaging/message/ParseRequestMessage.java` · `ParseResultMessage.java` javadoc | 존재하지 않는 `contracts/schema/parse-*.schema.json` 참조 (dangling) | `contracts/backend-ai/messaging.yml` 참조로 교체 |
| C8 | `service/port/ParseRequestPublisher.java` javadoc | 존재하지 않는 `asyncapi.yaml publishParseRequest` 참조 | 동일하게 messaging.yml 참조로 교체 |
| C9 | C1·C2가 낡게 만드는 주석 일괄 갱신 | `Paper.java` `FILE_KEY_FORMAT` javadoc(구형식·openapi 예시), `PaperRepository.markParsed` javadoc("PROCESSING이 아님"), `ParseResultService.apply`의 미반영 분기 주석 | 새 형식·새 CAS 조건(`UPLOADED 포함`) 기준으로 수정 |

계약 측(FE↔BE `openapi.yaml`의 `PaperCreated.fileKey` 예시)은 YMC-266에서 이미 갱신됐다.

부수 발견(이번 범위 아님, 정리 시점 재량): `common/error/ErrorCode.java`의 javadoc이
`project-docs/contracts/openapi.yaml`을 참조하나 실제 경로는 `contracts/frontend-backend/openapi.yaml`이다.

## 3. 상태 전이와 경합

### 결과 수신 terminal CAS (C2)

```sql
UPDATE paper
   SET status = :terminal, error_code = :errorCode, updated_at = :now
 WHERE id = :paperId
   AND status IN ('UPLOADED', 'PROCESSING');
```

`UPLOADED`를 포함하는 이유: request 발행 후 `PROCESSING` 커밋 전에 결과가 도착하거나
BE가 죽는 경우를 흡수한다 (ADR-002 Follow-ups에 이미 예고된 항목).

### `UPLOADED → PROCESSING` CAS (C3·C4)

C2만 하면 `markProcessing()`(load-modify-save)과 결과 리스너가 경쟁한다.
빠른 실패 결과가 `UPLOADED → FAILED`로 먼저 전이한 뒤, complete 흐름이 `PROCESSING`으로
덮어쓰거나 `IllegalStateException`으로 5xx를 반환할 수 있다. 따라서 이 전이도 CAS로 바꾼다.

```sql
UPDATE paper
   SET status = 'PROCESSING', updated_at = :now
 WHERE id = :paperId
   AND status = 'UPLOADED';
```

`PaperTransitions.markProcessing`의 결과 처리:

- **1 row** — 정상. `PROCESSING` 반환.
- **0 row + 재조회 결과 terminal** — 빠른 결과가 먼저 온 정상 경합. terminal 상태 반환 (5xx 아님).
- **0 row + 그 외 상태/레코드 없음** — 예상하지 못한 상태. 오류 처리.

### 중복 결과

결과 발행과 request ack 사이에 AI 워커가 죽으면 request가 재전달되어 결과가 중복될 수 있다.
C2의 조건부 전이가 흡수한다 — 이미 terminal이면 0 row, `ParseResultService.apply`는
WARN만 남기고 정상 소비한다 (현행 유지).

### 결과 소비(ack) 정책 — 기구현, 현행 유지

`ParseResultListener`는 payload를 String으로 받아 직접 역직렬화하며, 입력 성격에 따라 운명이 갈린다.

| 입력 | 처리 | 근거 |
|---|---|---|
| malformed JSON | WARN 후 정상 반환 = ack (영구 폐기) | 재시도해도 같은 결과 — 큐에 되돌리면 poison message |
| 계약 위반 (`contractViolation()`) | 동일하게 ack (영구 폐기) | 비복구 입력 |
| 유효 메시지 + 일시 장애(DB 연결 실패 등) | 예외 전파 = ack 안 함 → SQS 재전달 | 재시도하면 성공할 수 있음 |

양쪽 모두 `ParseResultConsumptionIntegrationTest`에 커버리지가 있다. 이번 범위의 변경 없음 —
spec 완결성을 위해 기록한다.

배선 요약: 발행은 SDK v2 동기 `SqsClient`, 수신은 spring-cloud-aws `@SqsListener`(비동기 클라이언트),
큐 이름 기본값은 `application.yml`의 `parse-requests`/`parse-results` — SSOT(messaging.yml)의
채널 이름·방향과 일치.

## 4. 테스트

기존 관례: 순수 단위는 `PaperTest`(JUnit5 + AssertJ), 통합은 `support/IntegrationTest`
베이스 (Testcontainers postgres + LocalStack, `givenPendingPaper`·`publishParseResult`·`awaitConsumed` 헬퍼).

| 검증 | 위치(예상) |
|---|---|
| 신규 Paper가 `uploads/{paperId}/original.pdf`를 생성한다 | `PaperTest` — 기존 `register는_uploads_ownerId_paperId_형식…` 테스트가 형식을 하드코딩하므로 함께 수정. `PaperRegistrationIntegrationTest`도 두 곳(응답 body 검증·저장 엔티티 검증)에서 구형식을 하드코딩하므로 함께 수정 |
| (제거) 엔티티 `markProcessing` 단위 테스트 | C5가 엔티티 메서드를 제거하므로 `PaperTest.MarkProcessing` nested 클래스(전이 성공·비-UPLOADED 거부)를 삭제. CAS 전이 검증은 아래 통합 테스트가 대체 |
| `PROCESSING` 상태의 결과가 terminal로 전이된다 | `ParseResultConsumptionIntegrationTest` (기존 케이스 유지) |
| `UPLOADED` 상태의 결과도 terminal로 전이된다 | `ParseResultConsumptionIntegrationTest` 신규 |
| 결과가 `markProcessing`보다 먼저 도착해도 complete가 5xx가 되지 않고 terminal을 반환한다 | `PaperUploadCompletionIntegrationTest` 신규 |
| 빠른 terminal 결과를 `PROCESSING`이 덮지 않는다 | 동상 |
| 중복 결과는 상태와 `updatedAt`을 다시 바꾸지 않는다 | `ParseResultConsumptionIntegrationTest` (기존 중복 수신 케이스 확장) |

## 5. 하지 않는 것

| 항목 | 근거 |
|---|---|
| `DocumentContent` 분리·SHA-256 중복 판정 | ADR-003 (YMC-278~285) |
| `UPLOADED` 정체 복구 배치 (transactional outbox 부재 보완) | YMC-284 |
| DLQ 최종 실패의 BE 자동 반영 (`FAILED` 종결 consumer) | 후속 결정 |
| `parse-results.result` 본문 해석 | FT-004 착수 전 미확정 — BE는 해석하지 않는다 |
| `result`의 object 타입 강제 (`contractViolation()`에 `result != null && !result.isObject()` 검사 추가) | 계약은 `type: object`지만 BE가 본문을 해석하지 않는 동안은 실익이 없고, 미확정 필드로 실험 중인 AI 메시지를 통째로 폐기할 위험이 있다. FT-004에서 BE가 `result`를 읽기 시작할 때 함께 추가한다 |
| 채팅이 파싱 산출물을 사용하게 하는 작업 | 별도 (현재 채팅은 `simple_agent`·`paperId` 미전달) |
