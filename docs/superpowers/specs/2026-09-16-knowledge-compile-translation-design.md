# 지식 컴파일 연동과 번역 사이드카 적재 설계

- 날짜: 2026-09-16
- 티켓: YMC-389
- 범위: BE(`app/be`) + FE(`app/fe`) + project-docs(계약) + infra(dev BE env). AI 변경 없음.
- 계약 SSOT: `project-docs/contracts/backend-ai/sqs/messaging.yml` 0.3.0(변경 없음), `document-package.yml` 0.1.0 → **0.2.0**, `contracts/frontend-backend/openapi.yaml`에 `translationStatus` 추가. 별도 project-docs PR 선행.
- 선행: YMC-372(ai#25, 파서 번역 제거·컴파일 워커), YMC-374(project-docs#59, 컴파일 SQS 계약), YMC-387(project-docs#60, FT-005·아트보드), YMC-388(infra#39, dev 큐·컴파일 워커 서비스)

## 1. 배경과 목표

ai#25부터 파서는 `frontend/document.json`에 `text_kor`를 넣지 않는다. 번역은 파싱 뒤 별도의 지식 컴파일 워커가 `frontend/translation-ko.json` 사이드카로 만든다.

이 설계는 BE가 파싱 완료 뒤 컴파일을 요청하고, 결과를 받아 번역을 블록에 병합하고, FE가 번역 준비 상태를 보여주게 한다. 본문과 채팅은 파싱 직후 열리고 번역만 뒤따라 붙는다.

## 2. 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 저장 형태 | `document`에 `compile_status`(REQUESTED / COMPLETED / FAILED) + `compile_error_code`. 응답의 `translationStatus`는 언어와 조합해 계산 | 컴파일은 번역·선행지식·viz를 한 번에 만들고 결과도 하나로 온다. 실패 코드도 번역 전용이 아니라 컴파일 단계 코드다. FT-008/009가 같은 컬럼을 쓴다 |
| 언어 위치 | `document.source_language`를 적재 때 `document_content`와 같은 값으로 복제 | `/status`는 서재가 논문마다 2초 폴링한다. 조인 없이 `document` 한 행으로 답한다 |
| 사이드카 병합 | `translated` 블록만 골라 `document_content_block.content`에 `textKor` 추가 | 원문·이미지·표 행은 건드리지 않는다. `(document_id, block_id)` 유니크 인덱스로 행을 찾고, 논문당 한 번 오는 이벤트라 부하가 없다. 재전달돼도 같은 결과 |
| 기존 논문 | 재컴파일하지 않는다. dev는 배포 뒤 SQL 한 번으로 `textKor`가 이미 있는 문서는 COMPLETED, 영어인데 없는 문서는 FAILED(`LEGACY_NO_TRANSLATION`)로 채운다 | 아직 dev다. prod에는 해당 논문이 없으므로 조회 코드에 블록 확인을 남기지 않는다 |
| null 응답 | `compile_status`가 null이면 PENDING | 파싱이 COMPLETED이면 컴파일은 반드시 뒤따른다. 발행 실패 뒤 재전달을 기다리는 창에서도 FE가 폴링을 이어가 READY를 받는다 |
| 병합 0건 | 사이드카가 없거나 깨졌거나 병합이 0건이어도 COMPLETED. 원인은 WARN | 컴파일 자체는 성공했다. FE는 READY라도 번역 블록이 없으면 버튼을 비활성으로 두고 폴링하지 않는다. 재컴파일 경로가 없으니 FAILED로 표시해도 복구 경로는 같다 |
| 발행 전 크래시 | REQUESTED 커밋 뒤 발행 전에 죽으면 PENDING 정체. 파싱 발행의 PROCESSING 정체와 같은 창이며 정체 정리 스윕 티켓 범위에 REQUESTED도 포함한다 | 창은 커밋과 SendMessage 사이 밀리초 단위. 발행을 먼저 하면 중복 컴파일(LLM 비용 2배)로 위험이 옮겨갈 뿐이다 |
| 파서 `text_kor` 경로 | 리더에서 제거. 계약 0.2.0에서도 삭제 | v0 생산자(파서)가 없어졌다. 코드는 git 이력, 사유는 계약 상단 주석 한 줄로 남긴다 |
| FE 폴링 | `translationStatus === 'PENDING'`일 때만 `/status`를 5초 간격. READY가 되면 `paper-content` 재조회 |  |

제외: 선행지식 하이라이트·viz 저장(FT-008/009 결정 뒤), 재파싱 경로(YMC-354), `manifest_key` 저장(재컴파일을 안 하므로 이번엔 불필요), 리스너 메트릭(기존 파싱 리스너도 없음. 경계 로그 계획과 함께).

## 3. 계약 변경 (project-docs)

### 3.1 `contracts/frontend-backend/openapi.yaml`

- 새 스키마 `TranslationStatus`: `NOT_APPLICABLE` / `PENDING` / `READY` / `FAILED`. 설명에 다음을 적는다.
  - 파싱 `status`와 별개다. 파싱이 COMPLETED인 채로 번역만 뒤따라 바뀐다.
  - NOT_APPLICABLE: 영어가 아닌 문서. PENDING: 번역 생성 중. READY: 번역이 본문에 있음. FAILED: 생성 실패, 자동 재시도 없음.
  - FE는 PENDING일 때만 폴링하고 READY면 본문을 재조회한다. 서재에는 표시하지 않는다.
- `PaperStatusResponse`·`PaperContentResponse`에 `translationStatus` 필수 필드 추가.
- `PaperTextContent.textKor` 설명을 "`translationStatus`가 READY인 영어 문서의 번역 대상 블록에만 존재한다. 제목·캡션·참고문헌·첫 섹션 이전 앞머리에는 없다"로 고친다.
- `PaperContentBlock.label` 설명의 "파서가 번역하지 않으므로" 문구를 컴파일 워커 기준으로 고친다.

### 3.2 `contracts/backend-ai/sqs/document-package.yml` 0.2.0

- 상단 주석: "0.1.0까지는 파서가 `frontend/document.json`에 `text_kor`를 넣었다(ai#20~#24). 0.2.0부터 번역은 지식 컴파일 산출물 `frontend/translation-ko.json`으로만 온다(ai#25)." 대조 revision을 ai `7412ac6`(2026-09-14)으로 갱신.
- `FrontendTextContent.text_kor` 삭제. `source_language` 처리 규칙에서 `text_kor` 관련 WARN 세 줄 삭제.
- `ManifestDocument.artifacts.frontend_translation_ko` 추가(optional). `path`만 읽는다. 컴파일 완료 전에 발행된 manifest에는 없다.
- 새 스키마 `TranslationSidecar`: BE가 읽는 부분집합.
  - `schema_version`(integer, 1), `blocks[]`.
  - `blocks[].block_id`(string), `translation_status`(`translated` / `not_translated`), `translated_block_content`(`{format: text, text_kor: string}` 또는 null).
  - 처리 규칙: `translated`이면서 `text_kor`가 비어 있으면 그 블록은 WARN 후 건너뛴다. `schema_version`이 1이 아니면 WARN 후 병합 없이 진행한다. `block_id`가 적재된 블록에 없으면 그 블록만 WARN 후 건너뛴다.

## 4. BE 설계

### 4.1 스키마 (`be/docs/db/document.sql`)

| 컬럼 | 타입 | 채우는 시점 |
|---|---|---|
| `source_language` | `varchar(8)` null | 본문 적재 때. `document_content.source_language`와 같은 값 |
| `compile_status` | `varchar(32)` null, `check in ('REQUESTED','COMPLETED','FAILED')` | 컴파일 발행·결과 수신 때 |
| `compile_error_code` | `varchar(255)` null | 실패 결과 수신 때. 발행·성공 때 null |

`alter table document add column if not exists ...` 3줄을 파일에 남기고, prod 첫 배포 DDL 목록에 올린다. `updated_at`은 "파싱 상태가 마지막으로 바뀐 시각"이므로 컴파일 전이에서는 갱신하지 않는다.

dev 배포 뒤 1회 SQL(사용자 실행):

```sql
update document d set source_language = c.source_language
  from document_content c where c.document_id = d.id and d.source_language is null;
update document d set compile_status = 'COMPLETED'
  where d.compile_status is null and exists (
    select 1 from document_content_block b
    where b.document_id = d.id and b.content ? 'textKor');
-- 영어인데 번역이 없는 기존 논문. null로 두면 PENDING으로 보여 화면이 폴링을 계속한다.
update document d set compile_status = 'FAILED', compile_error_code = 'LEGACY_NO_TRANSLATION'
  where d.compile_status is null and d.status = 'COMPLETED' and d.source_language = 'en';
```

`LEGACY_NO_TRANSLATION`은 워커 계약 코드가 아니라 BE 운영용 값이다. `compile_error_code`는 문자열 컬럼이라 enum 검사를 두지 않는다.

### 4.2 도메인

- `CompileStatus` enum(REQUESTED / COMPLETED / FAILED), `TranslationStatus` enum(API와 이름 1:1).
- `Document.translationStatus()` 한 곳에서 계산한다.

```
source_language != "en"  → NOT_APPLICABLE   (적재 전이라 언어가 null이면 여기 해당)
compile_status == null   → PENDING          (아직 요청 전. 발행 실패 뒤 재전달 대기 포함)
REQUESTED                → PENDING
COMPLETED                → READY
FAILED                   → FAILED
```

null을 PENDING으로 두는 이유: 파싱이 COMPLETED이면 컴파일 요청은 반드시 뒤따른다. 발행 실패로 REQUESTED를 null로 되돌린 창에서 FAILED를 답하면 FE가 폴링을 멈춰, 재전달이 성공해 READY가 돼도 열린 화면이 알지 못한다.

- `DocumentRepository`에 bulk 갱신 3개: `markCompileRequested(id)`(null → REQUESTED, CAS), `revertCompileRequested(id)`(REQUESTED → null), `markCompiled(id, status, errorCode)`(REQUESTED 또는 null → COMPLETED/FAILED). 기존 `markProcessing`과 같은 `@Modifying` 방식.
- `DocumentContentIngestService.ingest`가 `document.source_language`도 같은 트랜잭션에서 쓴다.

### 4.3 설정

`application.yml`의 `aws.sqs`에 `knowledge-compile-request-queue`(기본값 `knowledge-compile-requests`), `knowledge-compile-result-queue`(기본값 `knowledge-compile-results`) 추가. `AwsProperties.Sqs` 필드 2개 추가. 기본값이 LocalStack 큐 이름과 같아 로컬은 설정 변경이 없다. dev는 infra가 env `KNOWLEDGE_COMPILE_REQUEST_QUEUE_NAME`·`KNOWLEDGE_COMPILE_RESULT_QUEUE_NAME`을 주입한다(§6).

### 4.4 컴파일 요청 발행

- 포트 `KnowledgeCompileRequestPublisher`(service/port), 구현 `SqsKnowledgeCompileRequestPublisher`(infra/messaging). `SqsParseRequestPublisher`와 같은 구조(QueueUrl 지연 캐시, 직렬화 실패는 `IllegalStateException`). 메시지 `KnowledgeCompileRequestMessage(paper_id, manifest_key)`, `paper_id`는 `document.request_paper_id`.
- 호출 지점: `ParseResultService.apply`의 끝. 새 협력자 `KnowledgeCompileStarter.startIfCompleted(documentId, manifestKey)`.
  1. `document.status == COMPLETED`이고 본문이 적재돼 있을 때만 진행.
  2. `markCompileRequested` CAS로 이긴 호출만 발행. 이미 REQUESTED 이상이면 반환.
  3. 발행은 트랜잭션 밖. 실패하면 `revertCompileRequested` 후 예외를 던져 파싱 결과 메시지가 재전달되게 한다(`DocumentParsingStarter`와 같은 패턴). 재전달 때는 적재 여부 검사가 건너뛰고 발행만 다시 시도한다.
  4. REQUESTED 커밋 뒤 SendMessage 전에 프로세스가 죽으면 재전달이 "이미 REQUESTED"로 보고 건너뛰어 PENDING에 머문다. 파싱 발행의 PROCESSING 정체와 같은 창이다. 이번에 스윕을 만들지 않고, 정체 정리 스윕 티켓에 REQUESTED 정체(`updated_at`이 아니라 별도 기준이 필요하므로 그때 `compile_requested_at` 추가)를 포함한다.
- 언어와 무관하게 발행한다. 한국어 논문도 선행지식·viz 산출물은 필요하다. 번역 상태만 NOT_APPLICABLE로 계산된다.
- 파싱 결과가 이미 terminal이라 전이하지 못한 재전달에서도, COMPLETED이고 적재돼 있고 `compile_status`가 null이면 발행한다.

### 4.5 컴파일 결과 수신

- `KnowledgeCompileResultListener`(infra/messaging): `@SqsListener("${aws.sqs.knowledge-compile-result-queue}")`, 원문 String 수신. 역직렬화 실패·계약 위반은 WARN 후 반환(ack). 그 뒤 예외는 전파(재전달).
- `KnowledgeCompileResultMessage`: `ParseResultMessage`와 같은 관대한 역직렬화·`contractViolation()`. 실패 코드 enum은 `messaging.yml`의 `KnowledgeCompileError.code` 5개.
- `KnowledgeCompileResultService.apply(requestPaperId, status, errorCode, manifestKey)`:
  - `findByRequestPaperId`로 `document` 역조회. 없으면 WARN 후 반환.
  - `compile_status`가 이미 COMPLETED이거나 FAILED이면 중복 전달로 보고 INFO 후 반환. 파싱 `status`가 COMPLETED가 아니면 WARN 후 반환(파싱 실패 문서에 결과가 올 수는 없지만 방어).
  - `completed`: `DocumentTranslationMergeService.merge(documentId, manifestKey)` 뒤 `markCompiled(COMPLETED, null)`. 둘은 한 트랜잭션. INFO 로그에 병합 블록 수.
  - `failed`: `markCompiled(FAILED, errorCode)`. ERROR 로그. 파싱 상태는 건드리지 않고 자동 재요청도 없다.
- `DocumentTranslationMergeService`(service):
  1. `PaperPackageReader.readTranslations(manifestKey)`로 `Map<blockId, textKor>`를 받는다.
  2. 문서 블록을 한 번에 읽어 `block_id`로 맞춘 뒤 `content`에 `textKor`를 넣어 저장한다. 사이드카에만 있는 `block_id`, 저장된 `content.format`이 `text`가 아닌 블록(수식·표·이미지)은 WARN 후 건너뛴다.
  3. 사이드카가 manifest에 없거나 형식이 어긋나면 리더가 빈 Map을 돌려주고 WARN을 남긴다. 병합 없이 COMPLETED로 둔다(FT-005 Story 2: "사이드카가 없거나 형식이 어긋난 패키지도 경고만 남기고 번역 없는 상태로 서빙").
  4. 영어 문서인데 병합이 0건이면 WARN을 남기고 COMPLETED로 둔다. 응답은 READY지만 번역 블록이 없어 FE가 버튼을 비활성으로 두고 폴링하지 않는다. 사용자에게 보이는 결과는 FAILED와 툴팁 문구만 다르다.
- 병합 대상 라벨을 BE가 다시 걸러내지 않는다. 어떤 블록을 번역하는지는 워커가 정하고 사이드카의 `translated`가 그 결과다. BE는 대상 행이 text 블록인지만 확인한다.

### 4.6 리더 정리

`S3PaperPackageReader`에서 `text_kor` 저장과 WARN 세 가지(영어인데 번역 없음, 영어 아닌데 번역 있음, 대상 아닌 라벨에 번역 있음)를 지운다. `readTranslations(manifestKey)`를 추가한다. manifest를 다시 읽어 `artifacts.frontend_translation_ko.path`가 없으면 WARN 후 빈 Map, 있으면 사이드카를 읽어 `translated`이고 `text_kor`가 비어 있지 않은 블록만 담는다.

### 4.7 응답

`PaperStatusResponse`·`PaperContentResponse`에 `translationStatus` 추가. `PaperStatusView`·`PaperContentView`가 `document.translationStatus()`를 나른다. `complete` 응답은 같은 DTO라 자동 포함. `document`가 아직 없는 UPLOAD_PENDING 논문과 적재 전(언어 null) 문서는 계산 규칙에 따라 NOT_APPLICABLE이 된다.

## 5. FE 설계

- `src/api/types.ts`: `TranslationStatus` 타입, `PaperStatusResponse`·`PaperContentResponse`에 `translationStatus`.
- `StudyPage`의 `paper-status` 쿼리: `refetchInterval`을 `data.translationStatus === 'PENDING' ? 5000 : false`로 둔다. `StudyPageContent`에서 `translationStatus`가 PENDING에서 READY로 바뀌면 `queryClient.invalidateQueries({ queryKey: ['paper-content', paperId] })`. React Query가 기존 데이터를 보여주다 새 데이터로 바꾸므로 화면이 비지 않는다. 스크롤·선택 상태는 블록 데이터 교체와 무관하다.
- 번역 버튼 활성 조건: `translationStatus === 'READY'`이고 번역 블록이 하나 이상. 비활성 툴팁은 아트보드 문구 그대로.
  - NOT_APPLICABLE: "한국어 논문은 번역하지 않습니다"
  - PENDING: "번역을 준비하고 있습니다"
  - FAILED: "번역 생성에 실패했습니다"
  - READY인데 번역 블록이 없음(사이드카 없음·병합 0건): "이 논문은 번역이 준비되지 않았습니다". 폴링하지 않는다
- 저장된 배치(`localStorage`)는 READY가 되는 순간 그대로 적용된다(FT-005 Story 1: 번역이 없는 논문에서는 안 보기로 적용하고 저장값은 지우지 않는다).
- 서재 목록·타입은 건드리지 않는다.

## 6. 인프라

`infra/deploy/modules/environment/main.tf`의 BE env에 `KNOWLEDGE_COMPILE_REQUEST_QUEUE_NAME = module.data.knowledge_compile_requests_queue_name`, `KNOWLEDGE_COMPILE_RESULT_QUEUE_NAME = module.data.knowledge_compile_results_queue_name` 추가. BE IAM은 infra#39에 이미 두 큐 권한이 있다. env 변경이라 apply 뒤 CD 재배포가 필요하다. infra#39 머지 뒤 별도 PR.

로컬(`infra/local`)은 큐가 이미 있고 기본값이 같아 변경 없음. 수동 검증용으로 `publish-parse-result.sh` 옆에 `publish-compile-result.sh`를 둘지는 구현 때 필요하면 추가한다(계약 0.3.0 형식).

## 7. 테스트

단위

- `Document.translationStatus()` 계산 표(언어 null/en/ko × compile_status null/REQUESTED/COMPLETED/FAILED).
- `KnowledgeCompileResultMessage.contractViolation()`: 필수 필드 누락, 모르는 status, 모르는 error code, completed에 manifest_key 없음.
- `S3PaperPackageReaderTest.readTranslations`: 정상, manifest에 항목 없음, schema_version 불일치, `translated`인데 `text_kor` 없음.

통합(LocalStack, `IntegrationTest`)

- `LocalStackTestConfiguration`에 컴파일 큐 2개 추가, `IntegrationTest`에 `compileRequestQueueUrl()`·`compileResultQueueUrl()`·`publishCompileResult` 헬퍼.
- 파싱 완료 결과 → `document.source_language` 저장, `compile_status = REQUESTED`, 요청 큐에 `{paper_id, manifest_key}` 발행.
- 파싱 완료 결과 중복 전달 → 요청 한 번만 발행.
- 컴파일 완료 결과 → 블록에 `textKor` 병합, `/status`·`/content`가 READY. 사이드카에만 있는 `block_id`·text가 아닌 블록은 건너뜀.
- 컴파일 완료인데 manifest에 사이드카 항목 없음 → COMPLETED, 블록 불변, WARN.
- 파싱 완료 뒤 발행 실패(요청 큐 이름을 잘못 주입) → `compile_status` null 유지, `/status`는 PENDING, 메시지 재전달 뒤 발행 성공.
- 컴파일 실패 결과 → FAILED + 코드, 파싱 상태 유지, `/content`는 여전히 200.
- 컴파일 결과 중복 전달·모르는 paper_id·계약 위반 → ack, 상태 불변.
- 한국어 논문 → 요청은 발행되지만 응답은 NOT_APPLICABLE.
- 픽스처 `paper-package-translated`를 사이드카 형태(manifest에 `frontend_translation_ko`, `frontend/translation-ko.json`)로 바꾼다. `frontend/document.json`에서 `text_kor` 제거.

FE

- vitest: `translationStatus`별 툴팁 분기, PENDING일 때만 `refetchInterval`이 설정되는지, READY 전환 시 `paper-content` invalidate.
- 실화면은 dev 배포 뒤 확인(PENDING → READY 전환과 번역 표시).

## 8. 배포 순서

1. project-docs PR(계약) 머지.
2. infra PR(BE env) 머지·apply.
3. app PR 머지 → CD. prod DDL 목록에 `document` 컬럼 3개 추가.
4. dev DB에 §4.1 SQL 1회 실행(사용자). 실행 전까지 기존 영어 논문은 PENDING으로 보이며 화면을 열면 5초 폴링이 돈다.
5. dev에 영어 논문을 올려 PENDING → READY 전환과 번역 표시 확인.

## 9. Codex 리뷰 반영

스펙 초안에 대한 adversarial 리뷰 4건과 결정.

| 지적 | 결정 |
|---|---|
| REQUESTED 커밋 뒤 발행 전 크래시로 PENDING 정체 | 파싱 발행과 같은 창으로 받아들이고 정체 스윕 티켓에 포함(§4.4) |
| 사이드카 없음·깨짐이 READY로 노출 | COMPLETED 유지. FE가 READY라도 번역 블록이 없으면 비활성·폴링 없음(§4.5, §5) |
| null → FAILED 규칙이 재시도 창에서 FE 폴링을 멈춤 | null → PENDING으로 변경. dev SQL이 legacy를 COMPLETED/FAILED로 명시(§4.1, §4.2) |
| 병합 대상이 text 블록인지 검증 없음 | `content.format == text`만 병합(§4.5) |
