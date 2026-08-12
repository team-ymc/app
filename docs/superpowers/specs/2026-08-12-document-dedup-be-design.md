# Document 기반 PDF 중복 제거 — BE 구현 스펙

- 날짜: 2026-08-12 (같은 날 Codex 리뷰 반영 — 대표 원본 삭제 가드, ON CONFLICT 생성, 발행
  규칙을 전 경로로 확장, errorCode·updatedAt 파생 정정, 수용 갭 명시)
- 범위: BE(`app/be`) — checksum 검증 업로드 계약(0.3.0) 구현 + Paper/Document 분리.
  FE·AI·인프라 변경 없음.
- 계약 SSOT: `project-docs/contracts/frontend-backend/openapi.yaml` 0.3.0,
  `project-docs/contracts/backend-ai/sqs/messaging.yml` 0.2.0 (**메시지 스키마 변경 없음**)
- 결정 근거: ADR-003(Accepted, 2026-08-12), ADR-001. 계약 반영 PR: project-docs #32 (머지됨)

## 1. 목표와 불변식

동일한 PDF byte를 여러 사용자·파일명으로 등록해도:

- 사용자별 `Paper`는 별도 유지. 검증된 파일, 대표 원본, 파싱 상태·오류·산출물은 `Document` 공유.
- 같은 SHA-256의 PDF는 기본 파싱을 정상 경로에서 한 번만 실행. at-least-once 전달과 발행
  타임아웃 모호성에 의한 드문 중복 실행은 전제로 수용하고(§4 수용 갭 ②, ADR-002 §5),
  결과·산출물이 중복 저장되지 않는 것은 소비 멱등성이 보장.
- 외부 API·인가는 `paperId` 유지. `documentId`·checksum·공유 S3 key는 응답에 노출하지 않는다
  (응답 스키마를 바꾸지 않으므로 구조적으로 보장).
- BE↔AI 메시지는 기존 `paper_id`·`file_key` 유지.
- 중복 판정에는 **S3가 검증한 checksum만** 사용한다. 클라이언트 제출 값은 서명에만 쓰고 저장하지 않는다.

## 2. 저장 모델

### 신규 `document` 테이블

| 컬럼 | 타입·제약 | 설명 |
|---|---|---|
| `id` | uuid PK | BE가 insert 전 생성 (paperId와 동일 패턴) |
| `checksum_sha256` | varchar(44) not null, `uk_document_checksum` unique | S3가 검증한 표준 Base64 SHA-256. 동시 complete에서 Document 유일성의 근거 |
| `file_key` | varchar not null | 대표 원본 key. 최초 업로더의 `uploads/{paperId}/original.pdf`를 복제 없이 그대로 사용 (ADR-003) |
| `status` | varchar(32) not null | `DocumentStatus`: `UPLOADED → PROCESSING → COMPLETED / FAILED`. 검증된 객체가 있어야 Document가 생기므로 `UPLOAD_PENDING` 없음 |
| `error_code` | varchar null | AI 실패 코드 원문 (기존 `Paper.errorCode` 이동) |
| `request_paper_id` | uuid not null, unique index | AI 작업 상관키 = Document를 만든 최초 paperId. **FK 아님** — 대표 Paper 삭제 후 dangling 허용. 발행·결과 역조회가 이 컬럼만 본다 |
| `created_at` / `updated_at` | timestamptz | `updated_at`은 상태 변경 시각 |

### `paper` 변경

- `document_id` uuid null, FK → `document.id`, index 추가. 업로드 검증 전에는 null.
- **`status`·`error_code` 컬럼 제거.** 파싱 상태의 진실 원천을 Document 하나로 단일화한다.
  API의 `PaperStatus`는 조회 시 파생한다:

```text
paper.document_id == null  → UPLOAD_PENDING
paper.document_id != null  → document.status를 그대로 매핑 (이름 1:1)
updatedAt                  → GREATEST(paper.updated_at, document.updated_at)
```

- `error_code`는 내부 기록·로그용이다 — 계약 `PaperStatusResponse`에 errorCode 필드가 없어
  API 매핑 대상이 아니다.
- `updatedAt` 규칙은 계약 정의("이 Paper의 표시 상태가 마지막으로 바뀐 시각")를 만족한다:
  연결 시점엔 paper.updated_at이 갱신되고, 이후 파싱 전이부터는 document.updated_at이 최신이 된다.

- `PaperStatus` enum(API 계약 타입)과 `EXPIRED`는 그대로 두되 MVP 미사용 유지.
- `file_key`·`uk_paper_owner_filename`·`DUPLICATE_FILENAME` 판정은 변경 없음. `file_key`는
  "이번 등록의 업로드 대상 key"이며 중복 등록이면 객체가 삭제될 수 있다(계약 0.3.0 fileKey 설명과 일치).
- 기존 `PaperRepository.markUploaded/markProcessing/markParsed` CAS와 `PaperTransitions`는
  Document로 이사한다(§4). Paper에는 `linkDocument` CAS 하나만 남는다.

### 파싱 산출물 테이블

`paper_content`·`paper_content_block`·`paper_content_asset`의 키를 `paper_id` → `document_id`로
전환하고 테이블·엔티티도 `document_content*`로 개명한다. asset `s3_key`는 불투명 key로 Document당 한 번 저장돼 그대로
공유된다 — 대표 파싱의 패키지 경로에 paperId가 포함돼 있어도 무관하다.

### DDL 산출물 (Flyway 도입하지 않음 — 현행 docs/db 방식 유지)

- `docs/db/document.sql` 신설, `docs/db/paper.sql` 개정
- 누락돼 있던 산출물 DDL을 `docs/db/document_content.sql`로 보충 (기존 부채 해소)

## 3. 업로드 URL 발급

| # | 대상 | 변경 |
|---|---|---|
| U1 | `api/dto/CreatePaperRequest` | `checksumSha256` 추가: `@NotNull @Pattern("^[A-Za-z0-9+/]{43}=$")`. 형식 위반이 400 `VALIDATION_ERROR`인 게 계약과 일치하므로 "값 판정은 서비스" 철학의 **명시적 예외**(전용 에러 코드가 없음) |
| U2 | `service/port/FileStorage`·`infra/storage/S3FileStorage` | `presignUpload`에 checksum 파라미터 추가(포트 시그니처부터), `PutObjectRequest.checksumSHA256(...)`으로 서명에 포함 |
| U3 | `api/dto/PaperCreated`·`PaperRegistrationResult` | `uploadHeaders` 추가: `Content-Type`, `x-amz-checksum-sha256` (계약 required) |
| U4 | `api/PaperController`·`PaperRegistrationService` | 컨트롤러 → 서비스 → 포트로 checksum 전달. 서명에만 쓰고 **저장하지 않는다** |

검증 포인트(구현 중 확인): presigner가 `x-amz-checksum-sha256`을 실제 서명 헤더로 뽑는지,
SDK 2.31.x 기본 checksum 설정(`WHEN_SUPPORTED`)이 presign에 간섭하지 않는지 —
LocalStack `S3_SKIP_SIGNATURE_VALIDATION=0` 통합 테스트로 확인한다(§9).

## 4. complete 처리

```text
1. Paper 조회 → 404 / 소유자 불일치 403                       (기존 동일)
2. paper.document_id != null → 발행 규칙(아래) 실행 후 파생 상태 반환
   (멱등 재호출, HEAD 없음. 재호출이 정체 Document의 구제 창구가 된다)
3. HEAD(paper.file_key, ChecksumMode.ENABLED)
   - 객체 없음        → 409 UPLOAD_NOT_FOUND                   (기존 동일)
   - 크기 초과        → 객체 삭제 후 413 FILE_TOO_LARGE        (기존 동일)
   - checksum 없음    → 409 UPLOAD_CHECKSUM_MISSING            (신규, UPLOAD_PENDING 유지)
4. [Tx] checksum으로 document 조회
   - 없음: document 생성(UPLOADED, file_key=이번 객체, request_paper_id=이번 paperId)
           + paper.document_id 연결. 생성은 INSERT ... ON CONFLICT DO NOTHING(네이티브 쿼리,
           0 row = 동시 생성 패배)으로 한다 — unique 위반 예외에 의존하면 PostgreSQL이
           트랜잭션을 abort시켜 같은 Tx에서 "있음" 경로로 전환할 수 없다. 0 row면 재조회해
           "있음" 경로로 전환.
   - 있음: paper CAS 연결
           (UPDATE paper SET document_id=?, updated_at=? WHERE id=? AND document_id IS NULL)
           @Modifying 쿼리는 JPA auditing을 우회하므로 updated_at을 명시한다 — 이게 없으면
           기존 COMPLETED Document에 연결될 때 updatedAt 파생 규칙(§2)이 연결 이전 시각을
           반환한다. Document CAS들도 기존 Paper CAS 관용구대로 updated_at을 함께 갱신한다.
           0 row = 같은 Paper의 동시 complete가 먼저 연결함 → 발행 규칙 후 파생 상태 반환
5. [커밋 후] paper.file_key != document.file_key 인 경우에만 이번 업로드 객체 삭제.
   같으면 이 객체가 대표 원본이므로 절대 삭제하지 않는다(같은 Paper 동시 complete에서 패자가
   대표 원본을 지우는 사고 방지). 삭제 실패는 WARN만, complete는 성공 (ADR-003)
6. [Tx 밖] 발행 규칙(아래) 실행
7. 파생 상태 반환 — 기존 document면 UPLOADED~FAILED가 즉시 반환될 수 있다 (계약 0.3.0)
```

### 발행 규칙 — 생성·연결·멱등 재호출 공통 단일 규칙

```text
document.status != UPLOADED → 아무것도 하지 않는다 (이미 발행됐거나 종료)
document.status == UPLOADED →
  [Tx] CAS UPLOADED → PROCESSING 선점 커밋 (승자 1명)
  → 승자만 발행: paper_id = document.request_paper_id, file_key = document.file_key
  → 발행 실패 시 [Tx] PROCESSING → UPLOADED 되돌림(best-effort) 후 예외 전파(5xx)
```

- **선점 커밋 → 발행 → 실패 시 반납** 순서다. 발행을 트랜잭션 안에 두면 row 락이 외부 I/O에
  물리고 "발행 성공 후 커밋 실패"의 유령 발행이 생기므로, 전이를 커밋으로 확정한 뒤 발행한다.
- 이 순서는 ADR-002 시퀀스(UPLOADED 커밋 → 발행 → PROCESSING)와 다르다. Paper 시절엔 발행
  주체가 경로상 1명이라 순서가 무관했지만, Document는 여러 complete가 닿는 공유 자원이라
  선점이 발행에 선행해야 이중 발행이 구조적으로 차단된다. 이 편차는 ADR-003 보강에 기록한다.
- 정상 경로: 생성자가 선점·발행. 이후 complete들은 document가 PROCESSING 이상이라 그냥 통과.
- **구제 발행**: 발행 실패·크래시로 UPLOADED에 남은 document는 이후의 아무 complete(같은
  Paper의 재호출 포함)가 선점해 재발행한다 — 정체가 사용자들에게 전염되지 않는다. 발행 실패가
  5xx로 전파되므로 FE의 complete 재시도가 자연스러운 1차 구제 트리거다.
- 구제 발행도 **구제한 사용자가 아닌 `request_paper_id`로 발행**한다. AI 작업 식별자가
  Document당 하나로 고정되고 결과 역조회가 항상 성립한다.
- **수용 갭 ①**: 선점 커밋과 발행 사이의 프로세스 크래시는 PROCESSING 정체로 남는다(반납할
  주체가 없음). 발생 창이 극히 좁아 수용하고, 시간 기반 정리 스윕(UPLOADED·PROCESSING 정체
  재처리)은 중복 객체 재삭제와 묶어 후속 티켓으로 뺀다.
- **수용 갭 ②**: 발행이 실제로는 성공했는데 타임아웃으로 실패 처리되면 반납→구제로 중복 발행이
  가능하다. SQS at-least-once 전제와 결과 소비 멱등성이 이를 흡수한다(ADR-002 §5) — 최악
  비용은 재파싱 1회이고 결과·산출물은 중복 저장되지 않는다.

### 구현 배치

| # | 대상 | 변경 |
|---|---|---|
| C1 | `common/error/ErrorCode` | `UPLOAD_CHECKSUM_MISSING(HttpStatus.CONFLICT)` 추가 (계약에 이미 등재 — enum 한 줄) |
| C2 | `service/port/UploadedObjectMetadata` | `checksumSha256`(nullable) 필드 추가 |
| C3 | `S3FileStorage.head` | `ChecksumMode.ENABLED`로 HEAD, `response.checksumSHA256()` 매핑 |
| C4 | `domain/Document`·`DocumentRepository` | 신규. CAS: `markProcessing`, `markParsed(terminal, errorCode)`, `revertToUploaded` — 기존 Paper CAS 관용구(`@Modifying` JPQL, 변경 row 수 반환) 이식 |
| C5 | `service/DocumentTransitions` | 신규 — 기존 `PaperTransitions`처럼 트랜잭션 경계 분리 전용 빈. `PaperTransitions`는 제거 |
| C6 | `PaperRepository` | `linkDocument` CAS 추가, `markUploaded/markProcessing/markParsed` 제거 |
| C7 | `PaperUploadCompletionService` | 위 흐름으로 재작성. 발행은 기존처럼 커밋 후 Tx 밖 |

## 5. 파싱 결과 연결

| # | 대상 | 변경 |
|---|---|---|
| R1 | `service/ParseResultService` | `paper_id`로 Paper 대신 **`request_paper_id`로 Document 역조회**. CAS `markParsed`(UPLOADED/PROCESSING → terminal). 매칭 없음 → WARN + ack (기존 정책) |
| R2 | `service/PaperContentIngestService` | `documentId` 기준으로 전환. `isIngested(documentId)` + delete→insert 멱등 구조 유지 |
| R3 | `ParseResultListener` | payload 파싱·ack 정책 변경 없음 |

중복 메시지·재전달: 전이 성공 여부와 적재를 독립 판정하는 기존 구조를 그대로 이식하므로
결과가 중복 저장되지 않는다. 대표 Paper가 삭제돼도 R1는 Document 컬럼만 보므로 영향 없다.

## 6. 조회 경로

모든 API가 `paperId 소유권 확인 → document 따라가기`로 통일된다. 외부 요청·응답 형식 변경 없음.

| API | 변경 |
|---|---|
| status (`PaperStatusService`) | `paper LEFT JOIN document` → §2 파생 규칙으로 `PaperStatusView` 매핑 |
| 목록 (`PaperListService`) | 동일 파생. N+1 없이 join fetch 또는 projection 쿼리 |
| download (`PaperDownloadService`) | document 미연결 → 409 `UPLOAD_NOT_FOUND`. 연결 시 `presignDownload(document.file_key, paper.filename)` — 공유 원본이어도 **내 Paper의 파일명**으로 저장된다 |
| content (`PaperContentQueryService`) | `document.status != COMPLETED` → 409 `PAPER_NOT_READY`. 산출물은 `document_id`로 조회. `AssetUrlCache`는 `s3_key` 키라 그대로 재사용(공유 Document에서 적중률 상승) |
| chat (`PaperChatAccessValidator`) | `validateChatReady`의 COMPLETED 판정을 document 상태로. `ChatSession`은 `paper_id` 유지 — 구조 변경 없음 |

FE가 `UPLOAD_PENDING → COMPLETED` 같은 점프를 볼 수 있다는 것(순차 전이 가정 금지)은
계약 0.3.0에 이미 명시돼 있어 BE 추가 작업 없음.

## 7. 삭제와 수명 주기 — 원칙만 (삭제 API 없음, 추가하지 않는다)

- Paper 삭제 = `paper` row 삭제만. Document·대표 원본·산출물·`request_paper_id`에 영향 없음.
  FK 방향이 `paper → document`라 제약도 걸리지 않는다.
- 대표 Paper 삭제 후 `request_paper_id`는 dangling — 정상. 결과 소비·구제 발행 모두 성립.
- 참조 없는 Document 정리, 삭제 실패 중복 객체 정리 배치: 범위 외 (ADR-003 follow-up).

## 8. Migration — 기존 데이터 초기화로 확정 (2026-08-12)

§2 DDL만 반영하고 데이터 이관은 하지 않는다. local/dev는 스키마·데이터 재생성.
`ddl-auto: update`는 컬럼 제거·테이블 개명을 못 하므로 dev 반영은 재생성 또는 수동 SQL로 한다.
prod는 미출시 상태라 동일하게 재생성한다 — 출시 후였다면 사전 DDL 적용(`validate`) 절차가
필요하지만 현재 해당 없음.

기존 Paper당 Document를 백필하는 보존 경로(checksum NULL 허용)도 검토했으나, 기존 업로드
객체에는 S3 검증 checksum이 없어(소급 불가) 중복 제거에 참여할 수 없고 dev 데이터는 보존
가치가 없어 배제했다.

## 9. 테스트

기존 인프라(Testcontainers PostgreSQL + LocalStack, `S3_SKIP_SIGNATURE_VALIDATION=0`) 그대로.

| 시나리오 | 위치 |
|---|---|
| 최초 등록 → 파싱 시작 / complete 재호출 멱등 / 발행 실패 | `PaperUploadCompletionIntegrationTest` 확장 |
| checksum 서명 포함 presign / 실제 byte 불일치 → S3 BadDigest 거절 | `PaperRegistrationIntegrationTest` "다른 크기 PUT 거절" 패턴 확장 |
| S3 checksum 누락 → 409 `UPLOAD_CHECKSUM_MISSING` | completion 테스트 (checksum 없이 직접 PUT한 객체로 재현) |
| 동일 byte 재등록(다른 파일명·다른 사용자) / 동시 complete(다른 Paper 2건, 같은 Paper 재호출 경합 각각 — 후자는 대표 원본 미삭제 검증 포함) / PROCESSING·COMPLETED·FAILED 재사용 / 중복 객체 삭제 실패(스파이 주입) / 발행 실패 후 재호출·타 사용자에 의한 구제 발행 | 신규 `DocumentDedupIntegrationTest` |
| 대표 Paper 삭제 후 status·download·결과 소비 | 신규 (repository로 row 삭제 후 검증) |
| 결과 중복 수신·재전달·미지의 paper_id | `ParseResultConsumptionIntegrationTest`를 Document 기준으로 개정 |
| E2E: 등록→PUT→complete→발행→결과→COMPLETED | `PaperFlowE2ETest` 개정 (uploadHeaders 사용) |

## 10. 관측

- 신규 Document 생성, 기존 연결, 구제 발행, 중복 객체 삭제 실패를
  `paperId={}, documentId={}` 병기 INFO/WARN으로 남겨 두 ID의 연결을 추적 가능하게 한다.
- **checksum 전체와 presigned URL은 로그에 남기지 않는다.** documentId가 checksum의 1:1
  대리키라 추적에 충분하고, 유출 시 기존 산출물 접근 프로브에 쓰일 수 있다.
- MDC·구조화 로깅 도입 없음 — 기존 key=value 관용구 유지.

## 11. 하지 않는 것

- BE↔AI 메시지 스키마 변경 (`paper_id`·`file_key` 유지, `additionalProperties: false`)
- Paper 삭제 API 신설, Document 물리 삭제, 참조 없는 Document·잔여 객체 정리 배치
- outbox, Flyway 도입 (별도 티켓), MDC
- 파싱 버전(`DocumentParse`) 분리, FAILED Document 재파싱, 의미적 동일 논문 판정
- 클라이언트 checksum만으로 업로드 생략·기존 Document 연결 (ADR-003이 명시 금지)
- 기존 갭의 해소 (이관만 하고 동작 유지):
  - terminal 커밋 후 적재 영구 실패 시 "COMPLETED인데 본문 없음" — 재전달 재시도로 수렴하는
    기존 구조 유지, 소진 시 수동 복구
  - 결과의 first-terminal-wins 의미론 — completed와 DLQ발 failed가 경합하면 먼저 온 쪽이
    확정되는 기존 동작 유지 (변경은 ADR-002 영역)
- `ErrorCode`에 빠져 있는 `CHAT_USAGE_LIMIT_EXCEEDED` — 채팅 쪽 기존 부채, 별도 처리
