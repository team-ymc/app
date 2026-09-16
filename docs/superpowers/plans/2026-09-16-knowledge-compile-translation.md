# 지식 컴파일 연동과 번역 사이드카 적재 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 파싱 완료 뒤 BE가 지식 컴파일을 요청하고, 결과를 받아 번역 사이드카를 블록에 병합하며, FE가 `translationStatus`로 번역 준비 상태를 보여준다.

**Architecture:** BE는 기존 파싱 발행·수신 구조(포트 + SQS 어댑터 + 서비스, CAS 전이)를 그대로 복제해 컴파일 큐 한 쌍을 더 다룬다. `document`에 `source_language`·`compile_status`·`compile_error_code` 컬럼을 두고 응답의 `translationStatus`는 한 곳에서 계산한다. FE는 PENDING일 때만 상태를 폴링하고 READY가 되면 본문을 다시 받는다.

**Tech Stack:** Spring Boot 3 + Spring Data JPA(PostgreSQL jsonb) + spring-cloud-aws SQS + AWS SDK v2, Testcontainers(PostgreSQL·LocalStack), JUnit 5 + AssertJ + Awaitility. FE는 React 19 + TanStack Query 5 + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-16-knowledge-compile-translation-design.md`

## Global Constraints

- 계약 SSOT는 `project-docs/contracts/`뿐이다. 코드 안의 스펙 문서는 신뢰하지 않는다. 계약 PR이 먼저다.
- 커밋 제목은 `[YMC-389] type(scope): subject`. 목적별로 커밋을 나눈다(계약 / BE / FE). `Co-Authored-By`·`Generated with` 줄을 넣지 않는다.
- 코드 주석은 핵심 1~2줄. 주석에 티켓 번호·스펙 절 번호를 인용하지 않는다.
- BE 규칙(`be/CLAUDE.md`): 포트 인터페이스는 `service/port`, 구현은 `infra/`. 빈 주입은 `@RequiredArgsConstructor`. 엔티티는 `@Getter`만, 상태 변경은 의도 드러나는 메서드. 상태 전이는 `DocumentRepository`의 조건부 UPDATE(CAS).
- 응답 `translationStatus` 값: `NOT_APPLICABLE` / `PENDING` / `READY` / `FAILED`. DB `compile_status` 값: `REQUESTED` / `COMPLETED` / `FAILED`.
- 계산 규칙(스펙 §4.2): `source_language != "en"` → NOT_APPLICABLE, `compile_status == null` → PENDING, REQUESTED → PENDING, COMPLETED → READY, FAILED → FAILED.
- `updated_at`은 컴파일 전이에서 갱신하지 않는다.
- 큐 이름 기본값: `knowledge-compile-requests` / `knowledge-compile-results`. env `KNOWLEDGE_COMPILE_REQUEST_QUEUE_NAME` / `KNOWLEDGE_COMPILE_RESULT_QUEUE_NAME`.
- 컴파일 실패 코드 enum(계약 `messaging.yml` 0.3.0): `MANIFEST_KEY_INVALID`, `PAPER_ID_INVALID`, `PARSED_DOCUMENT_NOT_FOUND`, `PARSED_DOCUMENT_INVALID`, `KNOWLEDGE_COMPILE_RETRIES_EXHAUSTED`. BE 운영용 값 `LEGACY_NO_TRANSLATION`은 dev SQL에서만 쓴다.
- 사이드카 경로: manifest `artifacts.frontend_translation_ko.path`(패키지 상대 경로). 사이드카 `schema_version`은 1.
- BE 테스트: `cd be && ./gradlew test --tests '<FQCN>'`. 통합 테스트는 Docker(Testcontainers)가 필요하다. FE 테스트: `cd fe && npm test -- <파일>`, 타입 검사 `npm run typecheck`.
- 저장소별 브랜치 이름: `YMC-389-knowledge-compile-translation`. app 브랜치는 이미 있다(`5305b24`에 스펙 커밋). project-docs·infra 브랜치는 각 저장소 `origin/main`에서 만든다(infra는 infra#39가 아직 열려 있으면 `origin/YMC-388-knowledge-compile-queues`에서 만들고 머지 뒤 rebase).

---

## 파일 구조

### project-docs

- Modify: `contracts/frontend-backend/openapi.yaml` — `TranslationStatus` 스키마, 두 응답에 필드, `textKor` 설명, version 0.5.0 → 0.6.0.
- Modify: `contracts/backend-ai/sqs/document-package.yml` — 0.2.0. `text_kor` 제거, `frontend_translation_ko`·`TranslationSidecar` 추가.

### infra

- Modify: `deploy/modules/environment/main.tf` — BE env 두 줄.

### app/be (main)

- Modify: `src/main/java/com/ymc/paper/domain/Document.java` — 컬럼 3개, `recordSourceLanguage`, `translationStatus()`.
- Create: `src/main/java/com/ymc/paper/domain/CompileStatus.java`.
- Create: `src/main/java/com/ymc/paper/domain/TranslationStatus.java` — enum + `of(sourceLanguage, compileStatus)`.
- Modify: `src/main/java/com/ymc/paper/domain/DocumentRepository.java` — `markCompileRequested`, `revertCompileRequested`, `markCompiled`.
- Modify: `src/main/java/com/ymc/paper/domain/DocumentContentBlock.java` — `mergeTranslation(String)`.
- Modify: `src/main/java/com/ymc/paper/service/DocumentTransitions.java` — 세 전이의 트랜잭션 경계.
- Modify: `src/main/java/com/ymc/paper/service/DocumentContentIngestService.java` — `document.source_language` 기록.
- Modify: `src/main/java/com/ymc/common/config/AwsProperties.java`, `src/main/resources/application.yml` — 큐 이름 2개.
- Create: `src/main/java/com/ymc/paper/service/port/KnowledgeCompileRequestPublisher.java`.
- Create: `src/main/java/com/ymc/paper/infra/messaging/message/KnowledgeCompileRequestMessage.java`.
- Create: `src/main/java/com/ymc/paper/infra/messaging/SqsKnowledgeCompileRequestPublisher.java`.
- Create: `src/main/java/com/ymc/paper/service/KnowledgeCompileStarter.java`.
- Modify: `src/main/java/com/ymc/paper/service/ParseResultService.java` — 적재 뒤 컴파일 발행.
- Modify: `src/main/java/com/ymc/paper/service/port/PaperPackageReader.java` — `readTranslations`.
- Modify: `src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java` — `text_kor` 경로 제거, `readTranslations` 구현.
- Create: `src/main/java/com/ymc/paper/service/DocumentTranslationMergeService.java`.
- Create: `src/main/java/com/ymc/paper/infra/messaging/message/KnowledgeCompileResultMessage.java`.
- Create: `src/main/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultListener.java`.
- Create: `src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java`.
- Modify: `src/main/java/com/ymc/paper/service/PaperStatusView.java`, `PaperContentView.java`, `PaperDocumentViews.java`, `PaperContentQueryService.java`, `src/main/java/com/ymc/paper/api/dto/PaperStatusResponse.java`, `PaperContentResponse.java`, `src/main/java/com/ymc/paper/api/PaperController.java` — `translationStatus` 전달.
- Modify: `docs/db/document.sql` — 컬럼 3개 + dev SQL.

### app/be (test)

- Modify: `src/test/java/com/ymc/support/LocalStackTestConfiguration.java` — 컴파일 큐 2개.
- Modify: `src/test/java/com/ymc/support/IntegrationTest.java` — 큐 URL·발행 헬퍼, 스파이, drain, 픽스처 업로드.
- Modify: `src/test/resources/fixtures/paper-package-translated/**` — 사이드카 형태로 전환.
- Modify: `src/test/resources/fixtures/paper-package-badlang/frontend/document.json` — `text_kor` 제거.
- Create/Modify: 아래 각 Task의 테스트 파일.

### app/fe

- Modify: `src/api/types.ts`, `src/markdown/paperContent.ts`, `src/routes/StudyPage.tsx`.
- Create: `src/routes/study/translationStatus.ts` — 툴팁·폴링 간격 순수 함수.
- Modify/Create: `src/markdown/paperContent.test.ts`, `src/routes/StudyPage.test.tsx`, `src/routes/study/translationStatus.test.ts`.

---

## Task 1: 계약 — openapi `translationStatus`

**Files:**
- Modify: `project-docs/contracts/frontend-backend/openapi.yaml` (`info.version` 18행, `PaperContentResponse` 1271행 부근, `PaperTextContent.textKor` 1363행 부근, `PaperStatusResponse` 1415행 부근, `PaperStatus` 뒤)

**Interfaces:**
- Produces: `TranslationStatus` enum 스키마와 `PaperStatusResponse.translationStatus`·`PaperContentResponse.translationStatus`(required). BE Task 12와 FE Task 13이 이 이름을 그대로 쓴다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/geunhh/Desktop/team-ymc/project-docs
git fetch origin
git switch -c YMC-389-knowledge-compile-translation origin/main
```

- [ ] **Step 2: `info.version`을 0.6.0으로 올린다**

`  version: 0.5.0` → `  version: 0.6.0`.

- [ ] **Step 3: `PaperStatusResponse`에 필드를 추가한다**

```yaml
    PaperStatusResponse:
      type: object
      required: [paperId, status, updatedAt, translationStatus]
      properties:
        paperId:
          type: string
          format: uuid
          description: 서재는 행마다 폴링하므로 응답이 뒤섞일 수 있다 — 응답만으로 판별하기 위해 싣는다.
        status:
          $ref: "#/components/schemas/PaperStatus"
        translationStatus:
          $ref: "#/components/schemas/TranslationStatus"
        updatedAt:
          type: string
          format: date-time
          description: 파싱 상태가 마지막으로 바뀐 시각. 번역 상태 변화는 반영하지 않는다.
```

- [ ] **Step 4: `PaperStatus` 스키마 바로 뒤에 `TranslationStatus`를 추가한다**

```yaml
    TranslationStatus:
      type: string
      description: |
        전체 번역 준비 상태. 파싱 `status`와 별개다 — 파싱이 COMPLETED인 채로 번역만 뒤따라 바뀐다.
        번역은 파싱 뒤 지식 컴파일 단계가 만들고 BE가 결과를 받아 본문에 병합한다 (FT-005 Story 2).

        - NOT_APPLICABLE: 영어가 아닌 문서. 번역을 만들지 않는다
        - PENDING: 컴파일 요청 전이거나 진행 중
        - READY: 번역이 본문 조회 응답의 textKor에 있다
        - FAILED: 컴파일 실패. 자동으로 다시 요청하지 않는다

        FE는 학습 화면에서 PENDING일 때만 status를 폴링하고, READY가 되면 content를 다시 조회한다.
        서재에는 이 값을 표시하지 않는다.
      enum: [NOT_APPLICABLE, PENDING, READY, FAILED]
```

- [ ] **Step 5: `PaperContentResponse`에 필드를 추가한다**

`required: [paperId, title, sourceLanguage, schemaVersion, blocks, assets]` → `required: [paperId, title, sourceLanguage, translationStatus, schemaVersion, blocks, assets]`. `sourceLanguage` 속성 바로 뒤에:

```yaml
        translationStatus:
          $ref: "#/components/schemas/TranslationStatus"
```

- [ ] **Step 6: `PaperTextContent.textKor` 설명과 `label` 설명을 고친다**

```yaml
        textKor:
          type: string
          minLength: 1
          description: >-
            translationStatus가 READY인 영어 문서의 번역 대상 블록에만 존재한다. 제목 계열·캡션·
            참고문헌·첫 섹션 이전 앞머리(저자·소속)에는 없다. FE는 이 필드 유무로 블록별 번역 존재를 판단한다.
```

`PaperContentBlock.label` 설명의 `reference_content는 참고문헌 항목이라 파서가 번역하지 않으므로 textKor가 오지 않는다` → `reference_content는 참고문헌 항목이라 컴파일 워커가 번역하지 않으므로 textKor가 오지 않는다`.

- [ ] **Step 7: YAML 문법 확인**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('contracts/frontend-backend/openapi.yaml'))" && echo OK
```

Expected: `OK`

- [ ] **Step 8: 커밋**

```bash
git add contracts/frontend-backend/openapi.yaml
git commit -m "[YMC-389] docs(contracts): 논문 상태·본문 응답에 translationStatus 추가"
```

---

## Task 2: 계약 — `document-package.yml` 0.2.0

**Files:**
- Modify: `project-docs/contracts/backend-ai/sqs/document-package.yml`

**Interfaces:**
- Produces: `ManifestDocument.artifacts.frontend_translation_ko.path`, `TranslationSidecar` 스키마. BE Task 9의 record 필드명이 여기서 나온다.

- [ ] **Step 1: 상단 주석과 version을 바꾼다**

파일 맨 위 주석 블록을 다음으로 교체한다.

```yaml
# BE ↔ AI 파서 패키지 스키마
#
# - parse-results의 manifest_key가 가리키는 S3 document package 전체 구조는 여기서
#   정의하지 않는다. ai 저장소 README.md "Configure Document Parser"·"Configure Knowledge
#   Compile Worker" 절과 docs/S3_BUCKET_STRUCTURE_KO.md를 따른다 (대조 revision: ai
#   origin/main 7412ac6, 2026-09-14).
# - 이 파일은 그 패키지 중 BE가 읽는 부분집합만 JSON Schema로 정의한다. 패키지에는
#   여기 없는 필드가 더 있으므로 additionalProperties를 제한하지 않는다.
# - manifest_key와 parse-results·knowledge-compile-results 메시지의 관계는 messaging.yml이
#   소유한다.
# - 0.1.0까지는 파서가 frontend/document.json의 text 블록에 text_kor를 넣었다(ai#20~#24).
#   0.2.0부터 번역은 지식 컴파일 산출물 frontend/translation-ko.json으로만 온다(ai#25).

version: 0.2.0
```

- [ ] **Step 2: `source_language` 처리 규칙에서 `text_kor` 관련 WARN 세 줄을 지운다**

다음 세 항목을 삭제한다.

```
# - en인데 번역 대상 블록(text 블록 중 block_label이 reference·reference_content가
#   아닌 것) 중 text_kor가 없는 것이 있으면 WARN 로그를 남긴다.
# - en이 아닌데 text_kor가 있으면 WARN 로그를 남긴다.
# - block_label이 reference·reference_content인 블록이나 non-text 블록에 text_kor가
#   있으면 WARN 로그를 남긴다.
```

`SourceLanguage.description`의 `en일 때만 번역이 있다. block_label이 ... 부분 번역 패키지는 없다.` 문장을 `en일 때만 컴파일 단계가 번역을 만든다. 어떤 블록을 번역하는지는 컴파일 워커가 정하고 TranslationSidecar의 translation_status가 그 결과다.`로 바꾼다.

- [ ] **Step 3: `ManifestDocument.artifacts`에 사이드카 항목을 추가한다**

`frontend_document` 속성 뒤에:

```yaml
          frontend_translation_ko:
            type: object
            required: [path]
            description: >-
              지식 컴파일이 성공한 뒤 재발행된 manifest에만 있다. 컴파일 완료 전에 발행된
              manifest에는 키 자체가 없다. BE는 knowledge-compile-results의 completed 결과를
              받은 뒤 이 path로 사이드카를 읽는다.
            properties:
              path:
                type: string
                minLength: 1
                description: manifest.json 기준 frontend/translation-ko.json 상대 경로.
```

- [ ] **Step 4: `FrontendTextContent.text_kor`를 삭제한다**

`FrontendTextContent`는 `format`·`text`만 남긴다.

- [ ] **Step 5: 파일 끝에 `TranslationSidecar`를 추가한다**

```yaml
  TranslationSidecar:
    type: object
    description: >-
      artifacts.frontend_translation_ko.path가 가리키는 frontend/translation-ko.json 중 BE가
      읽는 부분집합. 처리 규칙: schema_version이 1이 아니면 WARN 후 병합 없이 진행한다.
      translated이면서 text_kor가 비어 있으면 그 블록은 WARN 후 건너뛴다. block_id가 적재된
      블록에 없거나 적재된 블록의 content.format이 text가 아니면 그 블록만 WARN 후 건너뛴다.
      어느 경우도 컴파일 완료 반영을 막지 않는다.
    required: [schema_version, blocks]
    properties:
      schema_version:
        type: integer
        const: 1
      blocks:
        type: array
        items:
          $ref: "#/schemas/TranslationSidecarBlock"

  TranslationSidecarBlock:
    type: object
    required: [block_id, translation_status]
    properties:
      block_id:
        type: string
        minLength: 1
        description: frontend/document.json의 block_id와 같은 값.
      translation_status:
        type: string
        enum: [translated, not_translated]
      translated_block_content:
        type: [object, "null"]
        description: translated일 때만 객체. not_translated이면 null.
        properties:
          format:
            const: text
          text_kor:
            type: string
            minLength: 1
```

- [ ] **Step 6: YAML 문법 확인**

```bash
python3 -c "import yaml; yaml.safe_load(open('contracts/backend-ai/sqs/document-package.yml'))" && echo OK
```

Expected: `OK`

- [ ] **Step 7: 커밋**

```bash
git add contracts/backend-ai/sqs/document-package.yml
git commit -m "[YMC-389] docs(contracts): 파서 패키지 0.2.0, 번역을 사이드카로 읽는 부분집합 정의"
```

푸시와 PR 생성은 사용자 승인 뒤에 한다. PR 본문은 `## 배경` · `## 변경` · `## 의존`(app PR 링크는 나중에).

---

## Task 3: infra — dev BE 태스크에 컴파일 큐 이름 주입

**Files:**
- Modify: `infra/deploy/modules/environment/main.tf:409-411`

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/geunhh/Desktop/team-ymc/infra
git fetch origin
gh pr view 39 --json state -q .state
```

`MERGED`이면 `git switch -c YMC-389-knowledge-compile-translation origin/main`, 아니면 `git switch -c YMC-389-knowledge-compile-translation origin/YMC-388-knowledge-compile-queues`.

- [ ] **Step 2: BE env에 두 줄 추가**

`PARSE_RESULT_QUEUE_NAME  = module.data.parse_results_queue_name` 바로 뒤에:

```hcl
    KNOWLEDGE_COMPILE_REQUEST_QUEUE_NAME = module.data.knowledge_compile_requests_queue_name
    KNOWLEDGE_COMPILE_RESULT_QUEUE_NAME  = module.data.knowledge_compile_results_queue_name
```

`terraform fmt`로 정렬을 맞춘다.

- [ ] **Step 3: 검증**

```bash
cd deploy/modules/environment && terraform fmt -check && terraform validate 2>&1 | tail -2
```

`terraform validate`는 init이 필요할 수 있다. 실패하면 `cd ../../dev && terraform init -backend=false && terraform validate`로 대신 확인한다. Expected: `Success!`

- [ ] **Step 4: 커밋**

```bash
git add deploy/modules/environment/main.tf
git commit -m "[YMC-389] feat(deploy): BE 태스크에 지식 컴파일 큐 이름 주입"
```

plan·apply와 PR은 사용자 승인 뒤. env 변경이라 apply 뒤 BE CD 재배포가 필요하다는 점을 PR `## 검증`에 적는다.

---

## Task 4: BE 도메인 — 컬럼 3개와 `translationStatus` 계산

**Files:**
- Create: `be/src/main/java/com/ymc/paper/domain/CompileStatus.java`
- Create: `be/src/main/java/com/ymc/paper/domain/TranslationStatus.java`
- Modify: `be/src/main/java/com/ymc/paper/domain/Document.java`
- Modify: `be/docs/db/document.sql`
- Test: `be/src/test/java/com/ymc/paper/domain/TranslationStatusTest.java`(신규), `be/src/test/java/com/ymc/paper/domain/DocumentTest.java`

**Interfaces:**
- Produces: `enum CompileStatus { REQUESTED, COMPLETED, FAILED }`, `enum TranslationStatus { NOT_APPLICABLE, PENDING, READY, FAILED; static TranslationStatus of(String sourceLanguage, CompileStatus compileStatus) }`, `Document.getSourceLanguage()`, `Document.getCompileStatus()`, `Document.getCompileErrorCode()`, `Document.recordSourceLanguage(String)`, `Document.translationStatus()`.

- [ ] **Step 1: 실패하는 테스트 작성**

`be/src/test/java/com/ymc/paper/domain/TranslationStatusTest.java`:

```java
package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TranslationStatusTest {

    @ParameterizedTest(name = "language={0}, compile={1} → {2}")
    @CsvSource(nullValues = "null", value = {
            "en,   null,      PENDING",
            "en,   REQUESTED, PENDING",
            "en,   COMPLETED, READY",
            "en,   FAILED,    FAILED",
            "ko,   null,      NOT_APPLICABLE",
            "ko,   COMPLETED, NOT_APPLICABLE",
            "und,  FAILED,    NOT_APPLICABLE",
            "null, null,      NOT_APPLICABLE",
            "null, COMPLETED, NOT_APPLICABLE",
    })
    void 언어와_컴파일_상태로_번역_상태를_계산한다(String language, CompileStatus compile, TranslationStatus expected) {
        assertThat(TranslationStatus.of(language, compile)).isEqualTo(expected);
    }
}
```

`DocumentTest.java`에 추가:

```java
    @Test
    void 새_document는_언어와_컴파일_상태가_없고_번역_상태는_NOT_APPLICABLE이다() {
        Document doc = Document.create(UUID.randomUUID(), "A".repeat(43) + "=",
                "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        assertThat(doc.getSourceLanguage()).isNull();
        assertThat(doc.getCompileStatus()).isNull();
        assertThat(doc.getCompileErrorCode()).isNull();
        assertThat(doc.translationStatus()).isEqualTo(TranslationStatus.NOT_APPLICABLE);
    }

    @Test
    void 언어를_기록하면_영어는_PENDING이_된다() {
        Document doc = Document.create(UUID.randomUUID(), "A".repeat(43) + "=",
                "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        doc.recordSourceLanguage("en");
        assertThat(doc.translationStatus()).isEqualTo(TranslationStatus.PENDING);
        doc.recordSourceLanguage("ko");
        assertThat(doc.translationStatus()).isEqualTo(TranslationStatus.NOT_APPLICABLE);
    }
```

- [ ] **Step 2: 실패 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test --tests 'com.ymc.paper.domain.TranslationStatusTest' --tests 'com.ymc.paper.domain.DocumentTest' 2>&1 | tail -15
```

Expected: 컴파일 오류(`CompileStatus`·`TranslationStatus` 없음).

- [ ] **Step 3: enum 두 개 작성**

`CompileStatus.java`:

```java
package com.ymc.paper.domain;

/** 지식 컴파일 단계의 상태. null은 아직 요청 전이다. 파싱 상태와 별개로 흐른다. */
public enum CompileStatus {
    REQUESTED,
    COMPLETED,
    FAILED
}
```

`TranslationStatus.java`:

```java
package com.ymc.paper.domain;

/** API의 translationStatus. 문서 언어와 컴파일 상태에서 계산하며 DB에 따로 저장하지 않는다. */
public enum TranslationStatus {
    NOT_APPLICABLE,
    PENDING,
    READY,
    FAILED;

    /**
     * 영어가 아니면 번역 대상이 아니다. 영어인데 컴파일 상태가 없으면 아직 요청 전이라 PENDING이다 —
     * 발행 실패 뒤 재전달을 기다리는 창에서 FAILED로 답하면 FE가 폴링을 멈춘다.
     */
    public static TranslationStatus of(String sourceLanguage, CompileStatus compileStatus) {
        if (!"en".equals(sourceLanguage)) {
            return NOT_APPLICABLE;
        }
        if (compileStatus == null) {
            return PENDING;
        }
        return switch (compileStatus) {
            case REQUESTED -> PENDING;
            case COMPLETED -> READY;
            case FAILED -> FAILED;
        };
    }
}
```

- [ ] **Step 4: `Document`에 컬럼과 메서드 추가**

`requestPaperId` 필드 선언 앞에 추가한다.

```java
    /** 본문 적재 때 document_content와 같은 값을 복제한다. 상태 폴링이 조인 없이 번역 상태를 계산하기 위해서다. */
    @Column(name = "source_language", length = 8)
    private String sourceLanguage;

    /** 지식 컴파일 상태. null = 아직 요청 전. */
    @Enumerated(EnumType.STRING)
    @Column(name = "compile_status", length = 32)
    private CompileStatus compileStatus;

    /** 컴파일 실패 코드 원문. 내부 기록용. */
    @Column(name = "compile_error_code")
    private String compileErrorCode;
```

클래스 끝(`create` 정적 팩토리 뒤)에 추가한다.

```java
    public void recordSourceLanguage(String sourceLanguage) {
        this.sourceLanguage = sourceLanguage;
    }

    public TranslationStatus translationStatus() {
        return TranslationStatus.of(sourceLanguage, compileStatus);
    }
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.TranslationStatusTest' --tests 'com.ymc.paper.domain.DocumentTest' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: DDL 문서 갱신**

`be/docs/db/document.sql`의 `create table document` 안, `request_paper_id` 줄 앞에 추가한다.

```sql
    -- 번역 상태 계산용. source_language는 적재 때 document_content에서 복제한다.
    source_language    varchar(8),
    compile_status     varchar(32)
        check (compile_status in ('REQUESTED', 'COMPLETED', 'FAILED')),
    compile_error_code varchar(255),
```

파일 끝에 추가한다.

```sql
-- 기존 DB 반영 (prod 첫 배포 전 필수):
-- alter table document add column if not exists source_language varchar(8);
-- alter table document add column if not exists compile_status varchar(32)
--     check (compile_status in ('REQUESTED', 'COMPLETED', 'FAILED'));
-- alter table document add column if not exists compile_error_code varchar(255);

-- dev 1회 SQL (배포 뒤). 파서 시절 번역이 이미 블록에 있는 문서는 COMPLETED, 영어인데 번역이 없는
-- 문서는 FAILED로 채운다. null로 두면 PENDING으로 보여 화면이 폴링을 계속한다.
-- update document d set source_language = c.source_language
--   from document_content c where c.document_id = d.id and d.source_language is null;
-- update document d set compile_status = 'COMPLETED'
--   where d.compile_status is null and exists (
--     select 1 from document_content_block b
--     where b.document_id = d.id and b.content ? 'textKor');
-- update document d set compile_status = 'FAILED', compile_error_code = 'LEGACY_NO_TRANSLATION'
--   where d.compile_status is null and d.status = 'COMPLETED' and d.source_language = 'en';
```

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/domain/CompileStatus.java be/src/main/java/com/ymc/paper/domain/TranslationStatus.java be/src/main/java/com/ymc/paper/domain/Document.java be/docs/db/document.sql be/src/test/java/com/ymc/paper/domain/TranslationStatusTest.java be/src/test/java/com/ymc/paper/domain/DocumentTest.java
git commit -m "[YMC-389] feat(paper): document에 언어·컴파일 상태 컬럼과 번역 상태 계산 추가"
```

---

## Task 5: BE — 컴파일 전이 CAS와 적재 시 언어 복제

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/DocumentRepository.java`
- Modify: `be/src/main/java/com/ymc/paper/service/DocumentTransitions.java`
- Modify: `be/src/main/java/com/ymc/paper/service/DocumentContentIngestService.java`
- Test: `be/src/test/java/com/ymc/paper/domain/DocumentPersistenceIntegrationTest.java`, `be/src/test/java/com/ymc/paper/service/DocumentContentIngestIntegrationTest.java`

**Interfaces:**
- Produces: `DocumentRepository.markCompileRequested(UUID) → int`, `revertCompileRequested(UUID) → int`, `markCompiled(UUID, CompileStatus, String errorCode) → int`. `DocumentTransitions.markCompileRequested(UUID) → boolean`, `revertCompileRequested(UUID) → boolean`, `markCompiled(UUID, CompileStatus, String) → boolean`. `DocumentContentIngestService.ingest`가 `document.source_language`를 함께 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

`DocumentPersistenceIntegrationTest.java` 클래스 끝에 추가:

```java
    @Test
    void 컴파일_요청_선점은_null에서만_1row고_반납은_REQUESTED에서만_1row다() {
        UUID id = insert(randomChecksum(), UUID.randomUUID());

        assertThat(tx.execute(s -> documentRepository.markCompileRequested(id))).isEqualTo(1);
        assertThat(tx.execute(s -> documentRepository.markCompileRequested(id))).isEqualTo(0);
        assertThat(documentRepository.findById(id).orElseThrow().getCompileStatus())
                .isEqualTo(CompileStatus.REQUESTED);

        assertThat(tx.execute(s -> documentRepository.revertCompileRequested(id))).isEqualTo(1);
        assertThat(tx.execute(s -> documentRepository.revertCompileRequested(id))).isEqualTo(0);
        assertThat(documentRepository.findById(id).orElseThrow().getCompileStatus()).isNull();
    }

    @Test
    void 컴파일_종결은_null이나_REQUESTED에서만_1row고_updatedAt을_바꾸지_않는다() {
        UUID id = insert(randomChecksum(), UUID.randomUUID());
        Instant before = documentRepository.findById(id).orElseThrow().getUpdatedAt();

        assertThat(tx.execute(s -> documentRepository.markCompiled(id, CompileStatus.FAILED, "PARSED_DOCUMENT_INVALID")))
                .isEqualTo(1);
        Document failed = documentRepository.findById(id).orElseThrow();
        assertThat(failed.getCompileStatus()).isEqualTo(CompileStatus.FAILED);
        assertThat(failed.getCompileErrorCode()).isEqualTo("PARSED_DOCUMENT_INVALID");
        assertThat(failed.getUpdatedAt()).isEqualTo(before);

        // 이미 종결됐으면 중복 결과는 아무것도 바꾸지 않는다
        assertThat(tx.execute(s -> documentRepository.markCompiled(id, CompileStatus.COMPLETED, null))).isEqualTo(0);
        assertThat(documentRepository.findById(id).orElseThrow().getCompileStatus()).isEqualTo(CompileStatus.FAILED);
    }
```

`DocumentContentIngestIntegrationTest.java`에 추가(기존 `source_language와_textKor가_있는_패키지는_그대로_적재된다` 테스트 뒤):

```java
    @Test
    void 적재하면_document에도_source_language가_복제된다() {
        Paper paper = givenProcessingPaper("lang-copy.pdf");
        UUID documentId = paper.getDocumentId();
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());

        ingestService.ingest(documentId, manifestKey);

        assertThat(documentRepository.findById(documentId).orElseThrow().getSourceLanguage()).isEqualTo("en");
        assertThat(documentRepository.findById(documentId).orElseThrow().translationStatus())
                .isEqualTo(TranslationStatus.PENDING);
    }
```

필요한 import: `com.ymc.paper.domain.CompileStatus`, `com.ymc.paper.domain.TranslationStatus`, `com.ymc.paper.domain.Document`, `java.time.Instant`.

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.DocumentPersistenceIntegrationTest' --tests 'com.ymc.paper.service.DocumentContentIngestIntegrationTest' 2>&1 | tail -15
```

Expected: 컴파일 오류(메서드 없음).

- [ ] **Step 3: 리포지토리 CAS 3개 추가**

`DocumentRepository.java`의 `markParsed` 뒤에 추가한다.

```java
    /** 컴파일 요청 선점. 승자만 발행한다. updated_at은 파싱 상태 시각이라 건드리지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.compileStatus = com.ymc.paper.domain.CompileStatus.REQUESTED
             where d.id = :id
               and d.compileStatus is null
            """)
    int markCompileRequested(@Param("id") UUID id);

    /** 발행 실패 시 선점 반납 — 파싱 결과 재전달이 다시 선점한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.compileStatus = null
             where d.id = :id
               and d.compileStatus = com.ymc.paper.domain.CompileStatus.REQUESTED
            """)
    int revertCompileRequested(@Param("id") UUID id);

    /** 컴파일 결과 수신 종결. null 포함 — 선점 커밋 전에 결과가 도착하는 경합을 흡수한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.compileStatus = :status,
                   d.compileErrorCode = :errorCode
             where d.id = :id
               and (d.compileStatus is null
                    or d.compileStatus = com.ymc.paper.domain.CompileStatus.REQUESTED)
            """)
    int markCompiled(
            @Param("id") UUID id,
            @Param("status") CompileStatus status,
            @Param("errorCode") String errorCode);
```

- [ ] **Step 4: `DocumentTransitions`에 경계 메서드 추가**

`markParsedAndSettle` 뒤에 추가한다.

```java
    /** 컴파일 요청 선점을 즉시 커밋한다. true면 이 호출만 발행 권한을 갖는다. */
    @Transactional
    public boolean markCompileRequested(UUID documentId) {
        return documentRepository.markCompileRequested(documentId) == 1;
    }

    /** 발행 실패 시 선점 반납. */
    @Transactional
    public boolean revertCompileRequested(UUID documentId) {
        return documentRepository.revertCompileRequested(documentId) == 1;
    }

    /** 컴파일 결과 종결. 호출자가 트랜잭션 안이면 거기에 참여한다. */
    @Transactional
    public boolean markCompiled(UUID documentId, CompileStatus status, String errorCode) {
        if (status == null || status == CompileStatus.REQUESTED) {
            throw new IllegalArgumentException("컴파일 종결 상태만 허용됩니다: " + status);
        }
        return documentRepository.markCompiled(documentId, status, errorCode) == 1;
    }
```

import `com.ymc.paper.domain.CompileStatus`.

- [ ] **Step 5: 적재 서비스가 언어를 복제하게 한다**

`DocumentContentIngestService`에 `DocumentRepository documentRepository` 필드를 추가하고, `ingest`의 `contentRepository.save(...)` 앞에:

```java
        // 상태 폴링이 조인 없이 번역 상태를 계산하도록 document에도 언어를 복제한다.
        documentRepository.findById(documentId).ifPresent(d -> d.recordSourceLanguage(pkg.sourceLanguage()));
```

import `com.ymc.paper.domain.DocumentRepository`.

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.DocumentPersistenceIntegrationTest' --tests 'com.ymc.paper.service.DocumentContentIngestIntegrationTest' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/domain/DocumentRepository.java be/src/main/java/com/ymc/paper/service/DocumentTransitions.java be/src/main/java/com/ymc/paper/service/DocumentContentIngestService.java be/src/test/java/com/ymc/paper/domain/DocumentPersistenceIntegrationTest.java be/src/test/java/com/ymc/paper/service/DocumentContentIngestIntegrationTest.java
git commit -m "[YMC-389] feat(paper): 컴파일 상태 CAS 전이와 적재 시 언어 복제"
```

---

## Task 6: BE — 컴파일 큐 설정과 요청 발행 어댑터

**Files:**
- Modify: `be/src/main/java/com/ymc/common/config/AwsProperties.java`
- Modify: `be/src/main/resources/application.yml:94-96`
- Create: `be/src/main/java/com/ymc/paper/service/port/KnowledgeCompileRequestPublisher.java`
- Create: `be/src/main/java/com/ymc/paper/infra/messaging/message/KnowledgeCompileRequestMessage.java`
- Create: `be/src/main/java/com/ymc/paper/infra/messaging/SqsKnowledgeCompileRequestPublisher.java`
- Modify: `be/src/test/java/com/ymc/support/LocalStackTestConfiguration.java`
- Modify: `be/src/test/java/com/ymc/support/IntegrationTest.java`
- Test: `be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileRequestPublishIntegrationTest.java`(신규)

**Interfaces:**
- Produces: `AwsProperties.Sqs(parseRequestQueue, parseResultQueue, knowledgeCompileRequestQueue, knowledgeCompileResultQueue)`. `interface KnowledgeCompileRequestPublisher { void publish(UUID paperId, String manifestKey); }`. 테스트 헬퍼 `compileRequestQueueUrl()`, `compileResultQueueUrl()`, `publishCompileResult(String rawJson)`, 스파이 `knowledgeCompileRequestPublisher`.

- [ ] **Step 1: 실패하는 테스트 작성**

`KnowledgeCompileRequestPublishIntegrationTest.java`:

```java
package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.service.port.KnowledgeCompileRequestPublisher;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.services.sqs.model.Message;

/** messaging.yml 0.3.0 KnowledgeCompileRequest wire 형식. */
class KnowledgeCompileRequestPublishIntegrationTest extends IntegrationTest {

    @Autowired
    KnowledgeCompileRequestPublisher publisher;

    @Test
    void paper_id와_manifest_key만_snake_case로_발행한다() throws Exception {
        UUID paperId = UUID.randomUUID();

        publisher.publish(paperId, "papers/" + paperId + "/manifest.json");

        List<Message> messages = receive(compileRequestQueueUrl(), 5);
        assertThat(messages).hasSize(1);
        JsonNode body = objectMapper.readTree(messages.get(0).body());
        assertThat(body.get("paper_id").asText()).isEqualTo(paperId.toString());
        assertThat(body.get("manifest_key").asText()).isEqualTo("papers/" + paperId + "/manifest.json");
        assertThat(body.size()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileRequestPublishIntegrationTest' 2>&1 | tail -15
```

Expected: 컴파일 오류.

- [ ] **Step 3: 설정 추가**

`AwsProperties.Sqs`:

```java
    public record Sqs(
            String parseRequestQueue,
            String parseResultQueue,
            String knowledgeCompileRequestQueue,
            String knowledgeCompileResultQueue) {}
```

`application.yml`의 `aws.sqs`:

```yaml
  sqs:
    parse-request-queue: ${PARSE_REQUEST_QUEUE_NAME:parse-requests}
    parse-result-queue: ${PARSE_RESULT_QUEUE_NAME:parse-results}
    knowledge-compile-request-queue: ${KNOWLEDGE_COMPILE_REQUEST_QUEUE_NAME:knowledge-compile-requests}
    knowledge-compile-result-queue: ${KNOWLEDGE_COMPILE_RESULT_QUEUE_NAME:knowledge-compile-results}
```

- [ ] **Step 4: 포트·메시지·어댑터 작성**

`KnowledgeCompileRequestPublisher.java`:

```java
package com.ymc.paper.service.port;

import java.util.UUID;

/** 지식 컴파일 요청 발행(BE → AI) 포트. 컴파일 선점 CAS에 성공한 호출만 발행한다. */
public interface KnowledgeCompileRequestPublisher {

    /** 실패 시 예외를 던진다. 삼키면 REQUESTED만 남고 컴파일이 시작되지 않는다. */
    void publish(UUID paperId, String manifestKey);
}
```

`KnowledgeCompileRequestMessage.java`:

```java
package com.ymc.paper.infra.messaging.message;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** messaging.yml 0.3.0 `KnowledgeCompileRequest`. wire 필드는 snake_case, 필드를 늘리지 않는다. */
public record KnowledgeCompileRequestMessage(
        @JsonProperty("paper_id") UUID paperId,
        @JsonProperty("manifest_key") String manifestKey) {
}
```

`SqsKnowledgeCompileRequestPublisher.java`:

```java
package com.ymc.paper.infra.messaging;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.common.config.AwsProperties;
import com.ymc.paper.infra.messaging.message.KnowledgeCompileRequestMessage;
import com.ymc.paper.service.port.KnowledgeCompileRequestPublisher;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** `knowledge-compile-requests` 큐 발행. 구조는 {@link SqsParseRequestPublisher}와 같다. */
@Component
@RequiredArgsConstructor
public class SqsKnowledgeCompileRequestPublisher implements KnowledgeCompileRequestPublisher {

    private final SqsClient sqs;
    private final ObjectMapper objectMapper;
    private final AwsProperties props;

    private volatile String queueUrl;

    @Override
    public void publish(UUID paperId, String manifestKey) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl())
                .messageBody(serialize(new KnowledgeCompileRequestMessage(paperId, manifestKey)))
                .build());
    }

    private String queueUrl() {
        String cached = queueUrl;
        if (cached == null) {
            cached = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                    .queueName(props.sqs().knowledgeCompileRequestQueue())
                    .build())
                    .queueUrl();
            queueUrl = cached;
        }
        return cached;
    }

    private String serialize(KnowledgeCompileRequestMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "knowledge-compile-request 직렬화에 실패했습니다: paperId=" + message.paperId(), e);
        }
    }
}
```

- [ ] **Step 5: 테스트 인프라에 큐 추가**

`LocalStackTestConfiguration.awsResourceBootstrap`의 마지막 `createQueue` 뒤에:

```java
            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(props.sqs().knowledgeCompileRequestQueue())
                    .build());
            // 결과 큐는 parse-results와 같은 이유로 visibility를 2초로 둔다.
            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(props.sqs().knowledgeCompileResultQueue())
                    .attributesWithStrings(Map.of(
                            QueueAttributeName.VISIBILITY_TIMEOUT.toString(), "2"))
                    .build());
```

`IntegrationTest.java`:

- 스파이 추가(`parseRequestPublisher` 아래):

```java
    @MockitoSpyBean
    protected KnowledgeCompileRequestPublisher knowledgeCompileRequestPublisher;
```

- `resetState()` 끝에:

```java
        drain(compileRequestQueueUrl());
        drain(compileResultQueueUrl());
```

- `parseResultQueueUrl()` 뒤에:

```java
    protected String compileRequestQueueUrl() {
        return queueUrl(awsProperties.sqs().knowledgeCompileRequestQueue());
    }

    protected String compileResultQueueUrl() {
        return queueUrl(awsProperties.sqs().knowledgeCompileResultQueue());
    }

    /** knowledge-compile-results에 컴파일 워커인 척 원문 그대로 발행한다. */
    protected void publishCompileResult(String rawJson) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(compileResultQueueUrl())
                .messageBody(rawJson)
                .build());
    }
```

import `com.ymc.paper.service.port.KnowledgeCompileRequestPublisher`.

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileRequestPublishIntegrationTest' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/common/config/AwsProperties.java be/src/main/resources/application.yml be/src/main/java/com/ymc/paper/service/port/KnowledgeCompileRequestPublisher.java be/src/main/java/com/ymc/paper/infra/messaging/message/KnowledgeCompileRequestMessage.java be/src/main/java/com/ymc/paper/infra/messaging/SqsKnowledgeCompileRequestPublisher.java be/src/test/java/com/ymc/support/LocalStackTestConfiguration.java be/src/test/java/com/ymc/support/IntegrationTest.java be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileRequestPublishIntegrationTest.java
git commit -m "[YMC-389] feat(paper): 지식 컴파일 요청 큐 설정과 SQS 발행 어댑터"
```

---

## Task 7: BE — 파싱 완료 뒤 컴파일 요청 발행

**Files:**
- Create: `be/src/main/java/com/ymc/paper/service/KnowledgeCompileStarter.java`
- Modify: `be/src/main/java/com/ymc/paper/service/ParseResultService.java:30-58`
- Test: `be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileStartIntegrationTest.java`(신규)

**Interfaces:**
- Consumes: `DocumentTransitions.markCompileRequested/revertCompileRequested`(Task 5), `KnowledgeCompileRequestPublisher`(Task 6).
- Produces: `KnowledgeCompileStarter.startIfCompleted(UUID documentId, String manifestKey)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`KnowledgeCompileStartIntegrationTest.java`:

```java
package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.TranslationStatus;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.services.sqs.model.Message;

/** 파싱 완료 결과 → 본문 적재 → knowledge-compile-requests 발행. */
class KnowledgeCompileStartIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("파싱 완료·적재 뒤 같은 paper_id와 manifest_key로 컴파일을 요청하고 REQUESTED가 된다")
    void publishesCompileRequestAfterIngest() throws Exception {
        Paper paper = givenProcessingPaper("compile.pdf");
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitCompileStatus(paper.getId(), CompileStatus.REQUESTED);
        List<Message> requests = receive(compileRequestQueueUrl(), 5);
        assertThat(requests).hasSize(1);
        JsonNode body = objectMapper.readTree(requests.get(0).body());
        assertThat(body.get("paper_id").asText()).isEqualTo(paper.getId().toString());
        assertThat(body.get("manifest_key").asText()).isEqualTo(manifestKey);
        assertThat(documentOf(paper).translationStatus()).isEqualTo(TranslationStatus.PENDING);
    }

    @Test
    @DisplayName("한국어 논문도 컴파일은 요청하지만 번역 상태는 NOT_APPLICABLE이다")
    void requestsCompileForNonEnglishToo() {
        Paper paper = givenProcessingPaper("korean.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());   // source_language 없음 → null

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitCompileStatus(paper.getId(), CompileStatus.REQUESTED);
        assertThat(receive(compileRequestQueueUrl(), 5)).hasSize(1);
        assertThat(documentOf(paper).translationStatus()).isEqualTo(TranslationStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("파싱 결과가 중복 전달돼도 컴파일 요청은 한 번만 발행된다")
    void duplicateParseResultPublishesOnce() {
        Paper paper = givenProcessingPaper("dup.pdf");
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());
        String message = """
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey);

        publishParseResult(message);
        awaitCompileStatus(paper.getId(), CompileStatus.REQUESTED);
        publishParseResult(message);
        awaitConsumed(parseResultQueueUrl());

        assertThat(receive(compileRequestQueueUrl(), 2)).hasSize(1);
        verify(knowledgeCompileRequestPublisher, times(1)).publish(eq(paper.getId()), any());
    }

    @Test
    @DisplayName("발행 실패: REQUESTED를 반납해 PENDING을 유지하고, 재전달에서 다시 발행한다")
    void publishFailureRevertsAndRetriesOnRedelivery() {
        Paper paper = givenProcessingPaper("publish-fail.pdf");
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());
        doThrow(new IllegalStateException("SQS 장애"))
                .doCallRealMethod()
                .when(knowledgeCompileRequestPublisher).publish(eq(paper.getId()), any());

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        // 첫 시도 실패 직후: 본문은 적재됐고 컴파일 상태는 null(=PENDING)
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            verify(knowledgeCompileRequestPublisher).publish(eq(paper.getId()), any());
            assertThat(documentContentIngestService.isIngested(paper.getDocumentId())).isTrue();
        });
        assertThat(documentOf(paper).translationStatus()).isEqualTo(TranslationStatus.PENDING);

        // 재전달(visibility 2초) 뒤 성공
        awaitCompileStatus(paper.getId(), CompileStatus.REQUESTED);
        assertThat(receive(compileRequestQueueUrl(), 5)).hasSize(1);
        verify(knowledgeCompileRequestPublisher, times(2)).publish(eq(paper.getId()), any());
    }

    @Test
    @DisplayName("파싱 실패 결과에는 컴파일을 요청하지 않는다")
    void failedParseDoesNotRequestCompile() {
        Paper paper = givenProcessingPaper("parse-failed.pdf");

        publishParseResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSE_RETRIES_EXHAUSTED","message":"x"}}
                """.formatted(paper.getId()));
        awaitConsumed(parseResultQueueUrl());

        assertThat(documentOf(paper).getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(documentOf(paper).getCompileStatus()).isNull();
        assertThat(receive(compileRequestQueueUrl(), 1)).isEmpty();
    }

    private Document documentOf(Paper paper) {
        return documentRepository.findByRequestPaperId(paper.getId()).orElseThrow();
    }

    private void awaitCompileStatus(UUID requestPaperId, CompileStatus expected) {
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(documentRepository.findByRequestPaperId(requestPaperId)
                        .orElseThrow().getCompileStatus()).isEqualTo(expected));
    }
}
```

`givenTranslatedPackageOnS3`는 이 시점에는 아직 파서 시절 픽스처(`text_kor` 포함)를 올린다. Task 11에서 사이드카 형태로 바뀌어도 이 테스트는 `source_language: en`만 필요하므로 그대로 통과한다.

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileStartIntegrationTest' 2>&1 | tail -20
```

Expected: `publishesCompileRequestAfterIngest` 등이 REQUESTED를 기다리다 타임아웃으로 실패.

- [ ] **Step 3: `KnowledgeCompileStarter` 작성**

```java
package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.service.port.KnowledgeCompileRequestPublisher;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 완료·적재 뒤 지식 컴파일 요청 발행. 선점 CAS 승자만 발행하고, 발행은 트랜잭션 밖이다.
 * 선점 커밋 뒤 발행 전에 프로세스가 죽으면 REQUESTED에 머문다 — 파싱 발행의 PROCESSING 정체와 같은 창이며
 * 정체 정리 스윕의 후속 범위다.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeCompileStarter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCompileStarter.class);

    private final DocumentRepository documentRepository;
    private final DocumentTransitions transitions;
    private final KnowledgeCompileRequestPublisher publisher;

    public void startIfCompleted(UUID documentId, String manifestKey) {
        Document document = documentRepository.findById(documentId).orElseThrow(
                () -> new IllegalStateException("존재하지 않는 document: " + documentId));
        if (document.getStatus() != DocumentStatus.COMPLETED || document.getCompileStatus() != null) {
            return;
        }
        if (!transitions.markCompileRequested(documentId)) {
            return;   // 다른 수신이 선점
        }
        try {
            publisher.publish(document.getRequestPaperId(), manifestKey);
        } catch (RuntimeException e) {
            revertBestEffort(documentId);
            log.warn("컴파일 요청 발행 실패, 선점 반납: documentId={}, requestPaperId={}",
                    documentId, document.getRequestPaperId(), e);
            throw e;
        }
        log.info("컴파일 요청 발행: documentId={}, requestPaperId={}, manifestKey={}",
                documentId, document.getRequestPaperId(), manifestKey);
    }

    private void revertBestEffort(UUID documentId) {
        try {
            transitions.revertCompileRequested(documentId);
        } catch (RuntimeException revertFailure) {
            log.warn("REQUESTED 반납 실패, 정체 가능: documentId={}", documentId, revertFailure);
        }
    }
}
```

- [ ] **Step 4: `ParseResultService`에서 호출**

필드 추가 `private final KnowledgeCompileStarter compileStarter;`. `apply`의 마지막 블록을 다음으로 교체한다.

```java
        if (terminal != DocumentStatus.COMPLETED || manifestKey == null) {
            return;
        }
        boolean completed = transitioned || documentRepository.findById(documentId)
                .map(d -> d.getStatus() == DocumentStatus.COMPLETED)
                .orElse(false);
        if (!completed) {
            return;
        }
        if (!ingestService.isIngested(documentId)) {
            ingestService.ingest(documentId, manifestKey);
            log.info("본문 적재 완료: requestPaperId={}, documentId={}", requestPaperId, documentId);
        }
        // 본문·채팅은 적재 직후 열리고, 번역은 컴파일 결과를 받은 뒤 붙는다. 발행 실패는 예외로 올려 재전달을 받는다.
        compileStarter.startIfCompleted(documentId, manifestKey);
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileStartIntegrationTest' --tests 'com.ymc.paper.infra.messaging.ParseResult*' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. 기존 파싱 결과 테스트도 그대로 통과해야 한다(컴파일 요청 큐에 메시지가 쌓이지만 `resetState`가 비운다).

- [ ] **Step 6: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/service/KnowledgeCompileStarter.java be/src/main/java/com/ymc/paper/service/ParseResultService.java be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileStartIntegrationTest.java
git commit -m "[YMC-389] feat(paper): 파싱 완료·적재 뒤 지식 컴파일 요청 발행"
```

---

## Task 8: BE — 사이드카 읽기 (`readTranslations`)

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/port/PaperPackageReader.java`
- Modify: `be/src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java`
- Create: `be/src/test/resources/fixtures/paper-package-translated/frontend/translation-ko.json`
- Modify: `be/src/test/resources/fixtures/paper-package-translated/manifest.json`
- Test: `be/src/test/java/com/ymc/paper/infra/parsing/S3PaperPackageReaderTest.java`

**Interfaces:**
- Consumes: 계약 Task 2의 필드명.
- Produces: `PaperPackageReader.readTranslations(String manifestKey) → Map<String, String>`(blockId → textKor, `translated`이고 `text_kor`가 비어 있지 않은 블록만. 사이드카가 없거나 형식이 어긋나면 빈 Map + WARN).

이 Task에서는 `frontend/document.json`의 `text_kor`를 아직 지우지 않는다(Task 10에서 정리). 사이드카 파일과 manifest 항목만 더한다.

- [ ] **Step 1: 픽스처 추가**

`fixtures/paper-package-translated/manifest.json`의 `artifacts`에 두 항목 추가(`manifest_version`은 3으로):

```json
{
  "manifest_version": 3,
  "document_id": "fixture-translated",
  "source_language": "en",
  "page_count": 1,
  "parser": {"provider": "paddleocr", "model": "PaddleOCR-VL-1.6"},
  "artifacts": {
    "structure_document": {"path": "structure/document.json", "media_type": "application/json", "schema_version": 2},
    "frontend_document": {"path": "frontend/document.json", "media_type": "application/json", "schema_version": 1},
    "structure_translation_ko": {"path": "structure/translation-ko.json", "media_type": "application/json", "schema_version": 1},
    "frontend_translation_ko": {"path": "frontend/translation-ko.json", "media_type": "application/json", "schema_version": 1}
  },
  "assets": {"path": "assets", "registry": "structure_document"}
}
```

`fixtures/paper-package-translated/frontend/translation-ko.json`(신규):

```json
{
  "schema_version": 1,
  "document_id": "fixture-translated",
  "source_language": "en",
  "target_language": "ko",
  "blocks": [
    {"block_id": "p0000-b0000", "translation_status": "not_translated", "translated_block_content": null},
    {"block_id": "p0000-b0001", "translation_status": "translated", "translated_block_content": {"format": "text", "text_kor": "우리는 새로운 구조를 제안한다."}},
    {"block_id": "p0000-b0002", "translation_status": "translated", "translated_block_content": {"format": "text", "text_kor": "영어로 된 본문 단락."}},
    {"block_id": "p0000-b0003", "translation_status": "not_translated", "translated_block_content": null},
    {"block_id": "p0000-b0004", "translation_status": "not_translated", "translated_block_content": null}
  ]
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`S3PaperPackageReaderTest.java` 클래스 끝(헬퍼 앞)에 추가:

```java
    @Test
    void 사이드카의_translated_블록만_blockId와_textKor로_돌려준다(CapturedOutput output) {
        Map<String, String> translations = reader.readTranslations("papers/translated/manifest.json");

        assertThat(translations).containsOnly(
                Map.entry("p0000-b0001", "우리는 새로운 구조를 제안한다."),
                Map.entry("p0000-b0002", "영어로 된 본문 단락."));
        assertThat(output.getOut()).doesNotContain("WARN");
    }

    @Test
    void manifest에_frontend_translation_ko가_없으면_빈_Map과_WARN이다(CapturedOutput output) {
        Map<String, String> translations = reader.readTranslations("papers/p1/manifest.json");

        assertThat(translations).isEmpty();
        assertThat(output.getOut()).contains("frontend_translation_ko");
    }

    @Test
    void 사이드카_schema_version이_1이_아니면_빈_Map과_WARN이다(CapturedOutput output) {
        Map<String, String> files = sidecarPackage("sv2", """
                {"schema_version":2,"blocks":[
                  {"block_id":"b0","translation_status":"translated","translated_block_content":{"format":"text","text_kor":"번역"}}
                ]}
                """);
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        assertThat(reader.readTranslations("papers/sv2/manifest.json")).isEmpty();
        assertThat(output.getOut()).contains("schema_version");
    }

    @Test
    void translated인데_text_kor가_비어_있으면_그_블록만_건너뛰고_WARN이다(CapturedOutput output) {
        Map<String, String> files = sidecarPackage("empty", """
                {"schema_version":1,"blocks":[
                  {"block_id":"b0","translation_status":"translated","translated_block_content":{"format":"text","text_kor":""}},
                  {"block_id":"b1","translation_status":"translated","translated_block_content":null},
                  {"block_id":"b2","translation_status":"translated","translated_block_content":{"format":"text","text_kor":"정상"}}
                ]}
                """);
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        assertThat(reader.readTranslations("papers/empty/manifest.json")).containsOnly(Map.entry("b2", "정상"));
        assertThat(output.getOut()).contains("b0").contains("b1");
    }

    @Test
    void 사이드카가_JSON이_아니면_빈_Map과_WARN이다(CapturedOutput output) {
        Map<String, String> files = sidecarPackage("broken", "{ not json");
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        assertThat(reader.readTranslations("papers/broken/manifest.json")).isEmpty();
        assertThat(output.getOut()).contains("translation-ko.json");
    }

    /** manifest에 사이드카 항목이 있고 본문은 최소인 패키지. */
    private static Map<String, String> sidecarPackage(String paperId, String sidecarJson) {
        Map<String, String> files = new HashMap<>();
        files.put("papers/" + paperId + "/manifest.json", """
                {
                  "manifest_version": 3,
                  "document_id": "%s",
                  "artifacts": {
                    "frontend_document": {"path": "frontend/document.json"},
                    "structure_document": {"path": "structure/document.json"},
                    "frontend_translation_ko": {"path": "frontend/translation-ko.json"}
                  }
                }
                """.formatted(paperId));
        files.put("papers/" + paperId + "/frontend/translation-ko.json", sidecarJson);
        return files;
    }
```

`PREFIX_TO_FIXTURE`에 `"papers/broken/"`이 이미 `paper-package-broken`으로 잡혀 있으므로, 위 `사이드카가_JSON이_아니면` 테스트는 `mapStorage`를 쓰기 때문에 충돌하지 않는다(`FAKE_STORAGE`가 아니라 `mapStorage(files)`).

- [ ] **Step 3: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.parsing.S3PaperPackageReaderTest' 2>&1 | tail -15
```

Expected: 컴파일 오류(`readTranslations` 없음).

- [ ] **Step 4: 포트에 메서드 추가**

`PaperPackageReader.java`:

```java
    /**
     * 컴파일 산출물 사이드카(frontend/translation-ko.json)에서 번역된 블록만 읽는다.
     *
     * @return blockId → text_kor. manifest에 사이드카가 없거나 형식이 어긋나면 WARN 후 빈 Map.
     *         manifest 자체를 못 읽으면 예외 — 파싱 때 읽은 같은 파일이라 일시 장애로 본다.
     */
    Map<String, String> readTranslations(String manifestKey);
```

import `java.util.Map`.

- [ ] **Step 5: 리더 구현**

`S3PaperPackageReader`:

- `Manifest.Artifacts` record에 필드 추가:

```java
        record Artifacts(
                @JsonProperty("frontend_document") Artifact frontendDocument,
                @JsonProperty("structure_document") Artifact structureDocument,
                @JsonProperty("frontend_translation_ko") Artifact frontendTranslationKo) {
        }
```

- record 두 개 추가(파일 끝 record 묶음에):

```java
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranslationSidecar(
            @JsonProperty("schema_version") Integer schemaVersion,
            List<TranslationSidecarBlock> blocks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranslationSidecarBlock(
            @JsonProperty("block_id") String blockId,
            @JsonProperty("translation_status") String translationStatus,
            @JsonProperty("translated_block_content") TranslatedBlockContent translatedBlockContent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranslatedBlockContent(@JsonProperty("text_kor") String textKor) {
    }
```

- `read` 메서드 뒤에 구현 추가:

```java
    @Override
    public Map<String, String> readTranslations(String manifestKey) {
        String prefix = packagePrefix(manifestKey);
        Manifest manifest = parse(fileStorage.readUtf8(manifestKey), Manifest.class, manifestKey);
        Manifest.Artifact sidecar = manifest.artifacts() == null ? null : manifest.artifacts().frontendTranslationKo();
        if (sidecar == null || sidecar.path() == null) {
            log.warn("manifest에 frontend_translation_ko가 없습니다, 번역 없이 진행: manifestKey={}", manifestKey);
            return Map.of();
        }
        String sidecarKey = prefix + sidecar.path();
        TranslationSidecar doc;
        try {
            doc = parse(fileStorage.readUtf8(sidecarKey), TranslationSidecar.class, sidecarKey);
        } catch (IllegalStateException e) {
            log.warn("번역 사이드카를 읽지 못했습니다, 번역 없이 진행: key={}", sidecarKey, e);
            return Map.of();
        }
        if (doc.schemaVersion() == null || doc.schemaVersion() != 1 || doc.blocks() == null) {
            log.warn("번역 사이드카 schema_version 불일치 또는 blocks 없음, 번역 없이 진행: key={}, schema_version={}",
                    sidecarKey, doc.schemaVersion());
            return Map.of();
        }
        Map<String, String> translations = new LinkedHashMap<>();
        for (TranslationSidecarBlock block : doc.blocks()) {
            if (!"translated".equals(block.translationStatus())) {
                continue;
            }
            String textKor = block.translatedBlockContent() == null ? null : block.translatedBlockContent().textKor();
            if (block.blockId() == null || textKor == null || textKor.isEmpty()) {
                log.warn("translated인데 text_kor가 없는 블록, 건너뜀: blockId={}, key={}", block.blockId(), sidecarKey);
                continue;
            }
            translations.put(block.blockId(), textKor);
        }
        return translations;
    }
```

import `java.util.LinkedHashMap`. `fileStorage.readUtf8`이 S3 `NoSuchKeyException`을 던지는 경우(manifest는 항목이 있는데 파일이 없음)는 `IllegalStateException`이 아니라 SDK 예외다. 이 경우도 "형식이 어긋난 패키지"로 보고 WARN 후 빈 Map을 돌려주기 위해 `catch (IllegalStateException e)`를 `catch (RuntimeException e)`로 둔다. manifest 읽기는 try 밖이라 그 실패는 그대로 전파된다.

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.parsing.S3PaperPackageReaderTest' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. 기존 `번역이_있는_패키지는_sourceLanguage와_textKor를_담고_WARN이_없다`도 아직 통과한다(`text_kor`는 Task 10에서 지운다).

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/service/port/PaperPackageReader.java be/src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java be/src/test/resources/fixtures/paper-package-translated be/src/test/java/com/ymc/paper/infra/parsing/S3PaperPackageReaderTest.java
git commit -m "[YMC-389] feat(paper): 번역 사이드카에서 블록별 text_kor 읽기"
```

---

## Task 9: BE — 사이드카 병합 서비스

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/DocumentContentBlock.java`
- Create: `be/src/main/java/com/ymc/paper/service/DocumentTranslationMergeService.java`
- Test: `be/src/test/java/com/ymc/paper/domain/DocumentContentTest.java`, `be/src/test/java/com/ymc/paper/service/DocumentTranslationMergeIntegrationTest.java`(신규)

**Interfaces:**
- Consumes: `PaperPackageReader.readTranslations`(Task 8).
- Produces: `DocumentContentBlock.mergeTranslation(String textKor) → boolean`(text 블록이면 병합 후 true, 아니면 false). `DocumentTranslationMergeService.merge(UUID documentId, String manifestKey) → int`(병합 블록 수, `@Transactional`).

- [ ] **Step 1: 실패하는 테스트 작성**

`DocumentContentTest.java`에 추가:

```java
    @Test
    void 텍스트_블록에_번역을_병합하면_textKor가_붙고_원문은_그대로다() {
        DocumentContentBlock block = DocumentContentBlock.of(DOCUMENT_ID, "b0", 0, "text", null,
                List.of(), MAPPER.createObjectNode().put("format", "text").put("text", "Body"));

        assertThat(block.mergeTranslation("본문")).isTrue();
        assertThat(block.getContent().get("text").asText()).isEqualTo("Body");
        assertThat(block.getContent().get("textKor").asText()).isEqualTo("본문");

        // 같은 값을 다시 병합해도 결과가 같다 (재전달 멱등)
        assertThat(block.mergeTranslation("본문")).isTrue();
        assertThat(block.getContent().get("textKor").asText()).isEqualTo("본문");
    }

    @Test
    void 텍스트가_아닌_블록에는_병합하지_않는다() {
        DocumentContentBlock formula = DocumentContentBlock.of(DOCUMENT_ID, "f0", 0, "display_formula", null,
                List.of(), MAPPER.createObjectNode().put("format", "formula").put("tex", "E=mc^2"));

        assertThat(formula.mergeTranslation("번역")).isFalse();
        assertThat(formula.getContent().has("textKor")).isFalse();
    }
```

`DocumentTranslationMergeIntegrationTest.java`:

```java
package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.ymc.paper.domain.DocumentContentBlock;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ExtendWith(OutputCaptureExtension.class)
class DocumentTranslationMergeIntegrationTest extends IntegrationTest {

    @Autowired
    DocumentTranslationMergeService mergeService;

    /** COMPLETED + 사이드카 있는 패키지로 적재까지 끝난 논문. */
    private Paper givenIngestedTranslatedPaper() {
        Paper paper = givenProcessingPaper("merge.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        return reload(paper.getId());
    }

    private List<DocumentContentBlock> blocksOf(UUID documentId) {
        return documentContentBlockRepository.findAllByDocumentIdOrderByGlobalOrderAsc(documentId);
    }

    @Test
    void translated_블록에만_textKor가_붙는다() {
        Paper paper = givenIngestedTranslatedPaper();
        String manifestKey = "papers/" + paper.getId() + "/manifest.json";

        int merged = mergeService.merge(paper.getDocumentId(), manifestKey);

        assertThat(merged).isEqualTo(2);
        List<DocumentContentBlock> blocks = blocksOf(paper.getDocumentId());
        assertThat(blocks.get(0).getContent().has("textKor")).isFalse();   // doc_title
        assertThat(blocks.get(1).getContent().get("textKor").asText()).contains("새로운 구조");
        assertThat(blocks.get(2).getContent().get("textKor").asText()).contains("영어로 된 본문");
        assertThat(blocks.get(3).getContent().has("textKor")).isFalse();   // reference_content
        assertThat(blocks.get(4).getContent().has("textKor")).isFalse();   // image
    }

    @Test
    void 두_번_병합해도_결과가_같다() {
        Paper paper = givenIngestedTranslatedPaper();
        String manifestKey = "papers/" + paper.getId() + "/manifest.json";

        mergeService.merge(paper.getDocumentId(), manifestKey);
        int second = mergeService.merge(paper.getDocumentId(), manifestKey);

        assertThat(second).isEqualTo(2);
        assertThat(blocksOf(paper.getDocumentId()).get(1).getContent().get("textKor").asText()).contains("새로운 구조");
    }

    @Test
    void 적재에_없는_blockId와_텍스트가_아닌_블록은_건너뛰고_WARN이다(CapturedOutput output) {
        Paper paper = givenIngestedTranslatedPaper();
        String prefix = "papers/" + paper.getId() + "/";
        // 사이드카를 덮어쓴다: 모르는 id 하나, 이미지 블록(p0000-b0004) 하나, 정상 하나
        s3.putObject(PutObjectRequest.builder().bucket(awsProperties.s3().bucket())
                        .key(prefix + "frontend/translation-ko.json").build(),
                RequestBody.fromString("""
                        {"schema_version":1,"blocks":[
                          {"block_id":"p0000-b0099","translation_status":"translated","translated_block_content":{"format":"text","text_kor":"없는 블록"}},
                          {"block_id":"p0000-b0004","translation_status":"translated","translated_block_content":{"format":"text","text_kor":"이미지에 번역"}},
                          {"block_id":"p0000-b0002","translation_status":"translated","translated_block_content":{"format":"text","text_kor":"정상 병합"}}
                        ]}
                        """));

        int merged = mergeService.merge(paper.getDocumentId(), prefix + "manifest.json");

        assertThat(merged).isEqualTo(1);
        List<DocumentContentBlock> blocks = blocksOf(paper.getDocumentId());
        assertThat(blocks.get(2).getContent().get("textKor").asText()).isEqualTo("정상 병합");
        assertThat(blocks.get(4).getContent().has("textKor")).isFalse();
        assertThat(output.getOut()).contains("p0000-b0099").contains("p0000-b0004");
    }

    @Test
    void 사이드카가_없으면_0건이고_블록은_그대로다() {
        Paper paper = givenProcessingPaper("no-sidecar.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenPackageOnS3(paper.getId());   // 사이드카 항목 없는 manifest
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);

        assertThat(mergeService.merge(paper.getDocumentId(), manifestKey)).isZero();
        assertThat(blocksOf(paper.getDocumentId())).allSatisfy(b -> assertThat(b.getContent().has("textKor")).isFalse());
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.DocumentContentTest' --tests 'com.ymc.paper.service.DocumentTranslationMergeIntegrationTest' 2>&1 | tail -15
```

Expected: 컴파일 오류.

- [ ] **Step 3: 엔티티 메서드 추가**

`DocumentContentBlock.java`의 `of` 뒤에:

```java
    /**
     * 사이드카 번역을 content에 붙인다. text 블록만 대상이다 — 수식·표·이미지에 온 번역은 워커 오류로 보고 거절한다.
     * 새 노드로 바꿔 넣어 JPA 변경 감지가 확실히 잡히게 한다.
     */
    public boolean mergeTranslation(String textKor) {
        Objects.requireNonNull(textKor, "textKor");
        if (!"text".equals(content.path("format").asText())) {
            return false;
        }
        ObjectNode merged = content.deepCopy();
        merged.put("textKor", textKor);
        this.content = merged;
        return true;
    }
```

import `com.fasterxml.jackson.databind.node.ObjectNode`. `content`는 `JsonNode`라 `deepCopy()`가 `JsonNode`를 돌려준다 — `ObjectNode merged = (ObjectNode) content.deepCopy();`로 캐스팅한다(text 블록의 content는 적재 때 항상 `ObjectNode`로 만든다).

- [ ] **Step 4: 병합 서비스 작성**

```java
package com.ymc.paper.service;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.DocumentContentBlock;
import com.ymc.paper.domain.DocumentContentBlockRepository;
import com.ymc.paper.service.port.PaperPackageReader;

import lombok.RequiredArgsConstructor;

/**
 * 컴파일 산출물 사이드카의 번역을 적재된 블록에 병합한다. translated 블록만 골라 해당 행의 content에
 * textKor를 더한다 — 원문·이미지·표 행은 손대지 않는다. 같은 값을 다시 병합해도 결과가 같아 재전달에 안전하다.
 */
@Service
@RequiredArgsConstructor
public class DocumentTranslationMergeService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTranslationMergeService.class);

    private final PaperPackageReader packageReader;
    private final DocumentContentBlockRepository blockRepository;

    /** @return 병합한 블록 수. 사이드카가 없거나 형식이 어긋나면 0. */
    @Transactional
    public int merge(UUID documentId, String manifestKey) {
        Map<String, String> translations = packageReader.readTranslations(manifestKey);
        if (translations.isEmpty()) {
            return 0;
        }
        Map<String, DocumentContentBlock> byId = blockRepository
                .findAllByDocumentIdOrderByGlobalOrderAsc(documentId).stream()
                .collect(Collectors.toMap(DocumentContentBlock::getBlockId, Function.identity()));

        int merged = 0;
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            DocumentContentBlock block = byId.get(entry.getKey());
            if (block == null) {
                log.warn("사이드카에만 있는 blockId, 건너뜀: blockId={}, documentId={}", entry.getKey(), documentId);
                continue;
            }
            if (!block.mergeTranslation(entry.getValue())) {
                log.warn("텍스트가 아닌 블록에 온 번역, 건너뜀: blockId={}, documentId={}", entry.getKey(), documentId);
                continue;
            }
            merged++;
        }
        return merged;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.DocumentContentTest' --tests 'com.ymc.paper.service.DocumentTranslationMergeIntegrationTest' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/domain/DocumentContentBlock.java be/src/main/java/com/ymc/paper/service/DocumentTranslationMergeService.java be/src/test/java/com/ymc/paper/domain/DocumentContentTest.java be/src/test/java/com/ymc/paper/service/DocumentTranslationMergeIntegrationTest.java
git commit -m "[YMC-389] feat(paper): 번역 사이드카를 적재된 텍스트 블록에 병합"
```

---

## Task 10: BE — 리더의 파서 `text_kor` 경로 제거

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java`
- Modify: `be/src/test/resources/fixtures/paper-package-translated/frontend/document.json`
- Modify: `be/src/test/resources/fixtures/paper-package-badlang/frontend/document.json`
- Modify: `be/src/test/java/com/ymc/paper/infra/parsing/S3PaperPackageReaderTest.java`
- Modify: `be/src/test/java/com/ymc/paper/service/DocumentContentIngestIntegrationTest.java`
- Modify: `be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java`
- Modify: `be/src/test/java/com/ymc/support/IntegrationTest.java`(`givenTranslatedPackageOnS3` 파일 목록·주석)

**Interfaces:**
- 변경 후 `read()`는 `frontend/document.json`의 `text_kor`를 무시한다. 번역은 Task 9의 `merge`로만 들어온다.

- [ ] **Step 1: 픽스처에서 `text_kor` 제거**

`paper-package-translated/frontend/document.json`의 세 블록에서 `, "text_kor": "..."`를 지운다. 결과:

```json
{
  "schema_version": 1,
  "document_id": "fixture-translated",
  "source_language": "en",
  "blocks": [
    {"block_id": "p0000-b0000", "page_index": 0, "global_block_order": 0, "block_label": "doc_title", "heading_level": 1, "section_path": ["p0000-b0000"], "block_content": {"format": "text", "text": "Attention Is All You Need"}},
    {"block_id": "p0000-b0001", "page_index": 0, "global_block_order": 1, "block_label": "abstract", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "text", "text": "We propose a new architecture."}},
    {"block_id": "p0000-b0002", "page_index": 0, "global_block_order": 2, "block_label": "text", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "text", "text": "Body paragraph in English."}},
    {"block_id": "p0000-b0003", "page_index": 0, "global_block_order": 3, "block_label": "reference_content", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "text", "text": "[1] Fixture reference."}},
    {"block_id": "p0000-b0004", "page_index": 0, "global_block_order": 4, "block_label": "image", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "image", "asset_key": "image_0"}}
  ]
}
```

`paper-package-badlang/frontend/document.json`의 `p0000-b0002` 블록에서 `, "text_kor": "[1] 픽스처 참고문헌."`를 지운다.

`IntegrationTest.givenTranslatedPackageOnS3`의 파일 목록에 `"frontend/translation-ko.json"`을 추가하고 주석을 `/** source_language=en이고 컴파일 사이드카가 있는 fixtures/paper-package-translated/를 올린다. */`로 바꾼다.

- [ ] **Step 2: 테스트를 새 동작에 맞춘다**

`S3PaperPackageReaderTest.java`:

- `번역이_있는_패키지는_sourceLanguage와_textKor를_담고_WARN이_없다`를 다음으로 교체:

```java
    @Test
    void read는_사이드카가_있어도_본문에_textKor를_넣지_않는다(CapturedOutput output) {
        ParsedPaperPackage pkg = reader.read("papers/translated/manifest.json");

        assertThat(pkg.sourceLanguage()).isEqualTo("en");
        assertThat(pkg.blocks()).allSatisfy(b -> assertThat(b.content().has("textKor")).isFalse());
        assertThat(output.getOut()).doesNotContain("WARN");
    }
```

- `frontend만_형식이_어긋나면_manifest값으로_폴백하고_정합성_A와_C_WARN을_남긴다`를 다음으로 교체:

```java
    @Test
    void frontend만_형식이_어긋나면_manifest값으로_폴백하고_형식_WARN만_남긴다(CapturedOutput output) {
        ParsedPaperPackage pkg = reader.read("papers/badlang/manifest.json");

        assertThat(pkg.sourceLanguage()).isEqualTo("en");
        assertThat(output.getOut()).contains("unknown");
        assertThat(output.getOut()).doesNotContain("불일치");
    }
```

- `en이_아닌데_text_kor가_있으면_정합성_B_WARN을_남긴다` 테스트를 통째로 삭제한다.
- `manifest와_frontend가_둘다_유효하고_다르면_불일치_WARN을_남기고_frontend값을_쓴다`의 인라인 JSON에서 `,"text_kor":"번역"`을 지운다.

`DocumentContentIngestIntegrationTest.java`의 `source_language와_textKor가_있는_패키지는_그대로_적재된다`를 교체:

```java
    @Test
    void source_language는_적재되고_번역은_적재_시점에_비어_있다() {
        Paper paper = givenProcessingPaper("translated.pdf");
        UUID documentId = paper.getDocumentId();
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());

        ingestService.ingest(documentId, manifestKey);

        assertThat(contentRepository.findById(documentId).orElseThrow().getSourceLanguage()).isEqualTo("en");
        List<DocumentContentBlock> blocks = blockRepository.findAllByDocumentIdOrderByGlobalOrderAsc(documentId);
        assertThat(blocks).allSatisfy(b -> assertThat(b.getContent().has("textKor")).isFalse());
    }
```

`PaperContentIntegrationTest.java`:

- `givenIngestedTranslatedPaper`가 병합까지 하도록 바꾼다. 필드 `@Autowired DocumentTranslationMergeService mergeService;` 추가.

```java
    /** COMPLETED + 사이드카 병합까지 끝난 논문. */
    private Paper givenIngestedTranslatedPaper() {
        Paper paper = givenProcessingPaper("translated-content.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);
        mergeService.merge(paper.getDocumentId(), manifestKey);
        return reload(paper.getId());
    }
```

- `번역이_있는_문서는_응답에_sourceLanguage와_textKor를_담는다`는 그대로 둔다(병합 뒤 같은 블록 위치에 `textKor`가 있다).

- [ ] **Step 3: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.parsing.S3PaperPackageReaderTest' --tests 'com.ymc.paper.service.DocumentContentIngestIntegrationTest' --tests 'com.ymc.paper.api.PaperContentIntegrationTest' 2>&1 | tail -20
```

Expected: 리더 테스트는 이미 통과할 수 있다(픽스처에서 `text_kor`를 지웠으므로). 이 단계의 목적은 리더 정리 전에 테스트 세트가 새 픽스처와 맞는지 확인하는 것이다. `frontend만_형식이_어긋나면` 테스트는 WARN 문구(`번역이 없는 블록`)가 아직 나오므로 통과하지만, Step 4 뒤에도 통과해야 한다.

- [ ] **Step 4: 리더에서 `text_kor` 경로 제거**

`S3PaperPackageReader.java`:

- `TRANSLATION_EXCLUDED_LABELS` 상수 삭제.
- `read`에서 `missingTranslationCount`·`nonEnTranslatedFound`·`ineligibleTranslatedFound` 변수, 루프 안의 `isTextFormat`/`isEligible`/`hasTextKor` 블록, 루프 뒤 WARN 세 개를 삭제한다. 루프 본문은 `resolveContent`·title·`blocks.add`만 남는다.
- `hasTextKor` 메서드 삭제.
- `textContent`에서 `if (hasTextKor(block)) { ... }` 삭제. 결과:

```java
    private JsonNode textContent(FrontendBlock block) {
        String text = block.blockContent().path("text").asText(null);
        if (text == null) {
            throw new IllegalStateException("text 블록에 text가 없습니다: blockId=" + block.blockId());
        }
        return objectMapper.createObjectNode().put("format", "text").put("text", text);
    }
```

- 클래스 Javadoc에 한 줄 추가: `번역은 이 파일이 아니라 컴파일 사이드카({@link #readTranslations})로만 들어온다.`
- 쓰지 않게 된 import(`Set`, `Locale`은 `ISO_LANGUAGES`에 아직 쓰인다) 정리.

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.parsing.S3PaperPackageReaderTest' --tests 'com.ymc.paper.service.DocumentContentIngestIntegrationTest' --tests 'com.ymc.paper.api.PaperContentIntegrationTest' --tests 'com.ymc.paper.service.DocumentTranslationMergeIntegrationTest' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java be/src/test/resources/fixtures be/src/test/java/com/ymc/paper/infra/parsing/S3PaperPackageReaderTest.java be/src/test/java/com/ymc/paper/service/DocumentContentIngestIntegrationTest.java be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java be/src/test/java/com/ymc/support/IntegrationTest.java
git commit -m "[YMC-389] refactor(paper): 파서 문서의 text_kor 적재 경로 제거"
```

---

## Task 11: BE — 컴파일 결과 리스너와 반영

**Files:**
- Create: `be/src/main/java/com/ymc/paper/infra/messaging/message/KnowledgeCompileResultMessage.java`
- Create: `be/src/main/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultListener.java`
- Create: `be/src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java`
- Test: `be/src/test/java/com/ymc/paper/infra/messaging/message/KnowledgeCompileResultMessageTest.java`(신규), `be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultConsumptionIntegrationTest.java`(신규)

**Interfaces:**
- Consumes: `DocumentTranslationMergeService.merge`(Task 9), `DocumentTransitions.markCompiled`(Task 5), 헬퍼 `publishCompileResult`(Task 6).
- Produces: `KnowledgeCompileResultService.apply(UUID requestPaperId, CompileStatus terminal, String errorCode, String manifestKey)`.

- [ ] **Step 1: 실패하는 단위 테스트 작성**

`KnowledgeCompileResultMessageTest.java`:

```java
package com.ymc.paper.infra.messaging.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.domain.CompileStatus;

class KnowledgeCompileResultMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID PAPER_ID = UUID.randomUUID();

    private KnowledgeCompileResultMessage parse(String json) throws Exception {
        return MAPPER.readValue(json, KnowledgeCompileResultMessage.class);
    }

    @Test
    void completed는_manifest_key가_있어야_유효하다() throws Exception {
        KnowledgeCompileResultMessage ok = parse("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"papers/x/manifest.json"}
                """.formatted(PAPER_ID));
        assertThat(ok.contractViolation()).isEmpty();
        assertThat(ok.terminalStatus()).isEqualTo(CompileStatus.COMPLETED);

        KnowledgeCompileResultMessage missing = parse("""
                {"paper_id":"%s","status":"completed","message":"ok"}
                """.formatted(PAPER_ID));
        assertThat(missing.contractViolation()).contains("manifest_key");
    }

    @Test
    void failed는_error_code가_있어야_유효하고_코드는_그대로_보존한다() throws Exception {
        KnowledgeCompileResultMessage ok = parse("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSED_DOCUMENT_INVALID","message":"x"}}
                """.formatted(PAPER_ID));
        assertThat(ok.contractViolation()).isEmpty();
        assertThat(ok.terminalStatus()).isEqualTo(CompileStatus.FAILED);
        assertThat(ok.errorCode()).isEqualTo("PARSED_DOCUMENT_INVALID");

        KnowledgeCompileResultMessage missing = parse("""
                {"paper_id":"%s","status":"failed"}
                """.formatted(PAPER_ID));
        assertThat(missing.contractViolation()).contains("error.code");
    }

    @Test
    void paper_id_status_누락과_모르는_status는_위반이다() throws Exception {
        assertThat(parse("{\"status\":\"completed\",\"manifest_key\":\"k\"}").contractViolation()).contains("paper_id");
        assertThat(parse("{\"paper_id\":\"%s\"}".formatted(PAPER_ID)).contractViolation()).contains("status");
        assertThat(parse("{\"paper_id\":\"%s\",\"status\":\"running\"}".formatted(PAPER_ID)).contractViolation())
                .contains("running");
    }

    @Test
    void 모르는_필드는_무시한다() throws Exception {
        KnowledgeCompileResultMessage m = parse("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"k","extra":{"a":1}}
                """.formatted(PAPER_ID));
        assertThat(m.contractViolation()).isEmpty();
    }
}
```

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`KnowledgeCompileResultConsumptionIntegrationTest.java`:

```java
package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentContentBlock;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.TranslationStatus;
import com.ymc.support.IntegrationTest;

/** knowledge-compile-results 소비. LocalStack에 실제로 발행하고 리스너가 소비하게 둔다. */
class KnowledgeCompileResultConsumptionIntegrationTest extends IntegrationTest {

    /** 파싱 완료·적재·컴파일 요청(REQUESTED)까지 끝난 영어 논문. 사이드카는 S3에 이미 있다. */
    private Paper givenRequestedPaper(String filename) {
        Paper paper = givenProcessingPaper(filename);
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        documentTransitions.markCompileRequested(paper.getDocumentId());
        return reload(paper.getId());
    }

    private String manifestKeyOf(Paper paper) {
        return "papers/" + paper.getId() + "/manifest.json";
    }

    private Document documentOf(Paper paper) {
        return documentRepository.findByRequestPaperId(paper.getId()).orElseThrow();
    }

    private List<DocumentContentBlock> blocksOf(Paper paper) {
        return documentContentBlockRepository.findAllByDocumentIdOrderByGlobalOrderAsc(paper.getDocumentId());
    }

    private void awaitCompileStatus(UUID requestPaperId, CompileStatus expected) {
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(documentRepository.findByRequestPaperId(requestPaperId)
                        .orElseThrow().getCompileStatus()).isEqualTo(expected));
    }

    @Test
    @DisplayName("completed: 사이드카를 블록에 병합하고 COMPLETED → 번역 상태 READY")
    void completedMergesAndMarksReady() {
        Paper paper = givenRequestedPaper("compile-ok.pdf");

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKeyOf(paper)));

        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
        Document document = documentOf(paper);
        assertThat(document.getCompileErrorCode()).isNull();
        assertThat(document.translationStatus()).isEqualTo(TranslationStatus.READY);
        assertThat(blocksOf(paper).get(1).getContent().get("textKor").asText()).contains("새로운 구조");
        assertThat(blocksOf(paper).get(3).getContent().has("textKor")).isFalse();
    }

    @Test
    @DisplayName("failed: FAILED와 코드를 기록하고 파싱 상태·블록은 그대로")
    void failedRecordsCodeOnly() {
        Paper paper = givenRequestedPaper("compile-failed.pdf");

        publishCompileResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSED_DOCUMENT_INVALID","message":"x"}}
                """.formatted(paper.getId()));

        awaitCompileStatus(paper.getId(), CompileStatus.FAILED);
        Document document = documentOf(paper);
        assertThat(document.getCompileErrorCode()).isEqualTo("PARSED_DOCUMENT_INVALID");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(document.translationStatus()).isEqualTo(TranslationStatus.FAILED);
        assertThat(blocksOf(paper)).allSatisfy(b -> assertThat(b.getContent().has("textKor")).isFalse());
    }

    @Test
    @DisplayName("completed인데 manifest에 사이드카가 없으면 병합 없이 COMPLETED")
    void completedWithoutSidecarStillCompletes() {
        Paper paper = givenProcessingPaper("no-sidecar.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenPackageOnS3(paper.getId());   // 사이드카 항목 없음, 언어 null
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);
        documentTransitions.markCompileRequested(paper.getDocumentId());

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));

        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
        assertThat(blocksOf(paper)).allSatisfy(b -> assertThat(b.getContent().has("textKor")).isFalse());
    }

    @Test
    @DisplayName("중복 전달: 이미 COMPLETED이면 아무것도 바꾸지 않고 소비한다")
    void duplicateResultIsConsumed() {
        Paper paper = givenRequestedPaper("compile-dup.pdf");
        String message = """
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKeyOf(paper));

        publishCompileResult(message);
        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
        publishCompileResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PAPER_ID_INVALID","message":"late"}}
                """.formatted(paper.getId()));
        awaitConsumed(compileResultQueueUrl());

        assertThat(documentOf(paper).getCompileStatus()).isEqualTo(CompileStatus.COMPLETED);
        assertThat(documentOf(paper).getCompileErrorCode()).isNull();
    }

    @Test
    @DisplayName("선점 커밋 전에 도착한 결과(compile_status null)도 반영한다")
    void resultBeforeRequestedIsApplied() {
        Paper paper = givenProcessingPaper("early.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKeyOf(paper)));

        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
    }

    @Test
    @DisplayName("알 수 없는 paper_id: 상태 변경 없이 소비한다")
    void unknownPaperIdIsConsumed() {
        UUID unknown = UUID.randomUUID();

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"papers/%s/manifest.json"}
                """.formatted(unknown, unknown));

        awaitConsumed(compileResultQueueUrl());
        assertThat(documentRepository.count()).isZero();
    }

    @ParameterizedTest(name = "비복구 입력이라 소비만 한다: {0}")
    @ValueSource(strings = {
            "{\"paper_id\": \"%s\", \"status\": \"running\"}",
            "{\"paper_id\": \"%s\", \"status\": \"completed\"}",       // manifest_key 누락
            "{\"paper_id\": \"%s\", \"status\": \"failed\"}",          // error.code 누락
            "{\"status\": \"completed\", \"manifest_key\": \"k\"}",   // paper_id 누락
            "{ this is not json",
    })
    void nonRecoverableMessagesAreConsumed(String template) {
        Paper paper = givenRequestedPaper("non-recoverable.pdf");

        publishCompileResult(template.contains("%s") ? template.formatted(paper.getId()) : template);

        awaitConsumed(compileResultQueueUrl());
        assertThat(documentOf(paper).getCompileStatus()).isEqualTo(CompileStatus.REQUESTED);
    }
}
```

- [ ] **Step 3: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.messaging.message.KnowledgeCompileResultMessageTest' --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileResultConsumptionIntegrationTest' 2>&1 | tail -15
```

Expected: 컴파일 오류.

- [ ] **Step 4: 메시지 record 작성**

`KnowledgeCompileResultMessage.java`:

```java
package com.ymc.paper.infra.messaging.message;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ymc.paper.domain.CompileStatus;

/**
 * messaging.yml 0.3.0 `KnowledgeCompileResult`. wire는 snake_case·status 소문자다.
 * completed는 manifest_key, failed는 error.code가 필수다. 수신은 관대하게 — 모르는 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeCompileResultMessage(
        @JsonProperty("paper_id") UUID paperId,
        String status,
        String message,
        @JsonProperty("manifest_key") String manifestKey,
        ErrorDetail error) {

    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorDetail(String code, String message) {
    }

    /** 재시도해도 달라지지 않는 계약 위반. 처리에 실제로 필요한 필드의 누락만 본다. */
    public Optional<String> contractViolation() {
        if (paperId == null) {
            return Optional.of("paper_id가 없습니다.");
        }
        if (status == null) {
            return Optional.of("status가 없습니다.");
        }
        if (terminalStatus() == null) {
            return Optional.of("컴파일 워커가 낼 수 없는 status입니다: " + status);
        }
        if (terminalStatus() == CompileStatus.COMPLETED && (manifestKey == null || manifestKey.isBlank())) {
            return Optional.of("status=completed인데 manifest_key가 없습니다.");
        }
        if (terminalStatus() == CompileStatus.FAILED && errorCode() == null) {
            return Optional.of("status=failed인데 error.code가 없습니다.");
        }
        return Optional.empty();
    }

    /** 계약의 소문자 status → CompileStatus. 계약에 없는 값이면 null. */
    public CompileStatus terminalStatus() {
        if (STATUS_COMPLETED.equals(status)) {
            return CompileStatus.COMPLETED;
        }
        if (STATUS_FAILED.equals(status)) {
            return CompileStatus.FAILED;
        }
        return null;
    }

    /** 실패 코드. 계약 enum 5종이지만 해석하지 않고 저장만 한다 — 새 코드가 늘어도 결과를 잃지 않는다. */
    public String errorCode() {
        if (error == null || error.code() == null || error.code().isBlank()) {
            return null;
        }
        return error.code();
    }
}
```

- [ ] **Step 5: 서비스 작성**

`KnowledgeCompileResultService.java`:

```java
package com.ymc.paper.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;

import lombok.RequiredArgsConstructor;

/**
 * 컴파일 결과 반영. 병합과 상태 종결을 한 트랜잭션으로 묶는다 — 병합만 커밋되고 상태가 REQUESTED로 남거나,
 * 상태만 COMPLETED가 되고 번역이 없는 상태를 만들지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeCompileResultService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCompileResultService.class);

    private final DocumentRepository documentRepository;
    private final DocumentTransitions transitions;
    private final DocumentTranslationMergeService mergeService;

    @Transactional
    public void apply(UUID requestPaperId, CompileStatus terminal, String errorCode, String manifestKey) {
        Optional<Document> found = documentRepository.findByRequestPaperId(requestPaperId);
        if (found.isEmpty()) {
            log.warn("컴파일 결과 미반영, 대응 document 없음: requestPaperId={}", requestPaperId);
            return;
        }
        Document document = found.get();
        UUID documentId = document.getId();
        if (document.getCompileStatus() == CompileStatus.COMPLETED
                || document.getCompileStatus() == CompileStatus.FAILED) {
            log.info("컴파일 결과 미반영, 이미 종결: requestPaperId={}, documentId={}, compileStatus={}",
                    requestPaperId, documentId, document.getCompileStatus());
            return;
        }
        if (document.getStatus() != DocumentStatus.COMPLETED) {
            log.warn("컴파일 결과 미반영, 파싱이 COMPLETED가 아님: requestPaperId={}, documentId={}, status={}",
                    requestPaperId, documentId, document.getStatus());
            return;
        }

        if (terminal == CompileStatus.COMPLETED) {
            int merged = mergeService.merge(documentId, manifestKey);
            if (merged == 0 && "en".equals(document.getSourceLanguage())) {
                log.warn("영어 문서인데 병합된 번역이 없습니다, READY로 응답되지만 번역 블록 없음: requestPaperId={}, "
                        + "documentId={}, manifestKey={}", requestPaperId, documentId, manifestKey);
            }
            transitions.markCompiled(documentId, CompileStatus.COMPLETED, null);
            log.info("컴파일 완료 반영: requestPaperId={}, documentId={}, mergedBlocks={}",
                    requestPaperId, documentId, merged);
            return;
        }
        transitions.markCompiled(documentId, CompileStatus.FAILED, errorCode);
        log.error("컴파일 실패 기록: requestPaperId={}, documentId={}, code={}", requestPaperId, documentId, errorCode);
    }
}
```

- [ ] **Step 6: 리스너 작성**

`KnowledgeCompileResultListener.java`:

```java
package com.ymc.paper.infra.messaging;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.infra.messaging.message.KnowledgeCompileResultMessage;
import com.ymc.paper.service.KnowledgeCompileResultService;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;

/**
 * `knowledge-compile-results` 인바운드 어댑터. ack 규칙은 {@link ParseResultListener}와 같다 —
 * 비복구 입력(malformed JSON, 계약 위반)은 WARN 후 정상 반환(ack), 일시 장애는 예외 전파(재전달).
 */
@Component
@RequiredArgsConstructor
public class KnowledgeCompileResultListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCompileResultListener.class);

    private final ObjectMapper objectMapper;
    private final KnowledgeCompileResultService resultService;

    @SqsListener("${aws.sqs.knowledge-compile-result-queue}")
    public void onCompileResult(String rawPayload) {
        KnowledgeCompileResultMessage message;
        try {
            message = objectMapper.readValue(rawPayload, KnowledgeCompileResultMessage.class);
        } catch (JacksonException e) {
            log.warn("knowledge-compile-result 역직렬화 실패, 폐기: payload={}", rawPayload, e);
            return;
        }

        Optional<String> violation = message.contractViolation();
        if (violation.isPresent()) {
            log.warn("knowledge-compile-result 계약 위반, 폐기: reason={}, payload={}", violation.get(), rawPayload);
            return;
        }

        resultService.apply(message.paperId(), message.terminalStatus(), message.errorCode(),
                message.manifestKey());
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.messaging.message.KnowledgeCompileResultMessageTest' --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileResultConsumptionIntegrationTest' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/infra/messaging/message/KnowledgeCompileResultMessage.java be/src/main/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultListener.java be/src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java be/src/test/java/com/ymc/paper/infra/messaging/message/KnowledgeCompileResultMessageTest.java be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultConsumptionIntegrationTest.java
git commit -m "[YMC-389] feat(paper): 지식 컴파일 결과 수신과 번역 병합·상태 반영"
```

---

## Task 12: BE — 응답에 `translationStatus`

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/PaperStatusView.java`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperContentView.java`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperDocumentViews.java:36-40`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperContentQueryService.java:76-77`
- Modify: `be/src/main/java/com/ymc/paper/api/dto/PaperStatusResponse.java`
- Modify: `be/src/main/java/com/ymc/paper/api/dto/PaperContentResponse.java`
- Modify: `be/src/main/java/com/ymc/paper/api/PaperController.java:114-116`
- Test: `be/src/test/java/com/ymc/paper/api/PaperStatusPollingIntegrationTest.java`, `be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java`

**Interfaces:**
- Consumes: `Document.translationStatus()`(Task 4).
- Produces: JSON `translationStatus` 필드(두 응답 모두). FE Task 13이 읽는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`PaperStatusPollingIntegrationTest.java`에 추가:

```java
    @Test
    @DisplayName("번역 상태: 적재 전 NOT_APPLICABLE → 영어 적재 뒤 PENDING → 컴파일 완료 READY → 실패 FAILED")
    void reportsTranslationStatus() throws Exception {
        Paper paper = givenProcessingPaper("translation-status.pdf");
        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.translationStatus").value("NOT_APPLICABLE"));

        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.translationStatus").value("PENDING"));

        documentTransitions.markCompileRequested(paper.getDocumentId());
        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.translationStatus").value("PENDING"));

        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null);
        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.translationStatus").value("READY"));
    }

    @Test
    @DisplayName("번역 상태: 컴파일 실패는 FAILED, 파싱 상태는 COMPLETED 유지")
    void reportsFailedTranslation() throws Exception {
        Paper paper = givenProcessingPaper("translation-failed.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.FAILED, "PARSED_DOCUMENT_INVALID");

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.translationStatus").value("FAILED"))
                .andExpect(jsonPath("$.compileErrorCode").doesNotExist());
    }

    @Test
    @DisplayName("업로드 대기 논문의 번역 상태는 NOT_APPLICABLE")
    void pendingUploadIsNotApplicable() throws Exception {
        Paper paper = givenPendingPaper("upload-pending.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.status").value("UPLOAD_PENDING"))
                .andExpect(jsonPath("$.translationStatus").value("NOT_APPLICABLE"));
    }
```

`import com.ymc.paper.domain.CompileStatus;`를 추가한다.

`PaperContentIntegrationTest.java`의 `번역이_있는_문서는_응답에_sourceLanguage와_textKor를_담는다`에 기대 추가:

```java
                .andExpect(jsonPath("$.translationStatus").value("READY"))
```

`givenIngestedTranslatedPaper`가 병합 뒤 `documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null);`도 호출하게 바꾼다. 그리고 `번역_도입_전_패키지는_응답의_sourceLanguage가_명시적으로_null이다`에 `assertThat(body).contains("\"translationStatus\":\"NOT_APPLICABLE\"");`를 추가한다.

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.PaperStatusPollingIntegrationTest' --tests 'com.ymc.paper.api.PaperContentIntegrationTest' 2>&1 | tail -15
```

Expected: `translationStatus` jsonPath 없음으로 실패.

- [ ] **Step 3: 뷰·DTO·컨트롤러 수정**

`PaperStatusView`:

```java
public record PaperStatusView(UUID paperId, PaperStatus status, TranslationStatus translationStatus, Instant updatedAt) {
}
```

`PaperDocumentViews.statusView`:

```java
    public PaperStatusView statusView(Paper paper) {
        Document document = documentOf(paper).orElse(null);
        TranslationStatus translationStatus = document == null
                ? TranslationStatus.NOT_APPLICABLE : document.translationStatus();
        return new PaperStatusView(paper.getId(), derivedStatus(paper, document), translationStatus,
                derivedUpdatedAt(paper, document));
    }
```

`PaperContentView`에 `TranslationStatus translationStatus` 컴포넌트를 `sourceLanguage` 뒤에 추가. `PaperContentQueryService.getContent`의 마지막 `return`:

```java
        return new PaperContentView(paper.getId(), content.getTitle(), content.getSourceLanguage(),
                document.translationStatus(), content.getSchemaVersion(), blocks, assets);
```

`PaperStatusResponse`:

```java
public record PaperStatusResponse(
        UUID paperId, PaperStatus status, TranslationStatus translationStatus, Instant updatedAt) {
}
```

`PaperController.toResponse`:

```java
        return new PaperStatusResponse(view.paperId(), view.status(), view.translationStatus(), view.updatedAt());
```

`PaperContentResponse`에 `TranslationStatus translationStatus` 컴포넌트를 `sourceLanguage` 뒤에 추가하고 `from`에서 `view.translationStatus()`를 넘긴다.

`PaperStatusView`·`PaperContentView`·두 DTO에 `import com.ymc.paper.domain.TranslationStatus;`.

`PaperUploadCompletionService.complete`가 돌려주는 `PaperStatusView`를 만드는 곳이 `views.statusView(paper)`가 아니라면 그곳도 새 생성자에 맞춘다. 확인:

```bash
grep -rn "new PaperStatusView(" be/src/main/java
```

- [ ] **Step 4: 컴파일·테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.*' 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/service/PaperStatusView.java be/src/main/java/com/ymc/paper/service/PaperContentView.java be/src/main/java/com/ymc/paper/service/PaperDocumentViews.java be/src/main/java/com/ymc/paper/service/PaperContentQueryService.java be/src/main/java/com/ymc/paper/api/dto/PaperStatusResponse.java be/src/main/java/com/ymc/paper/api/dto/PaperContentResponse.java be/src/main/java/com/ymc/paper/api/PaperController.java be/src/test/java/com/ymc/paper/api/PaperStatusPollingIntegrationTest.java be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java
git commit -m "[YMC-389] feat(paper): 상태·본문 응답에 translationStatus 추가"
```

- [ ] **Step 6: BE 전체 테스트**

```bash
./gradlew test 2>&1 | tail -8
```

Expected: `BUILD SUCCESSFUL`. 실패하면 이 Task 안에서 고친 뒤 `[YMC-389] fix(paper): ...`로 커밋.

---

## Task 13: FE — 타입·본문 모델·상태 헬퍼

**Files:**
- Modify: `fe/src/api/types.ts`
- Modify: `fe/src/markdown/paperContent.ts`
- Create: `fe/src/routes/study/translationStatus.ts`
- Test: `fe/src/markdown/paperContent.test.ts`, `fe/src/routes/study/translationStatus.test.ts`(신규)

**Interfaces:**
- Produces: `type TranslationStatus = 'NOT_APPLICABLE' | 'PENDING' | 'READY' | 'FAILED'`, `PaperStatusResponse.translationStatus`, `PaperContentResponse.translationStatus`, `PaperContent.translationStatus`, `translationRefetchInterval(status: TranslationStatus | undefined): number | false`, `translationDisabledReason(status: TranslationStatus | undefined, hasTranslation: boolean): string`, `TRANSLATION_POLL_MS = 5000`.

- [ ] **Step 1: 실패하는 테스트 작성**

`fe/src/routes/study/translationStatus.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { translationRefetchInterval, translationDisabledReason, TRANSLATION_POLL_MS } from './translationStatus';

describe('translationRefetchInterval', () => {
  it('PENDING일 때만 5초 폴링이고 나머지는 폴링하지 않는다', () => {
    expect(translationRefetchInterval('PENDING')).toBe(TRANSLATION_POLL_MS);
    expect(translationRefetchInterval('READY')).toBe(false);
    expect(translationRefetchInterval('FAILED')).toBe(false);
    expect(translationRefetchInterval('NOT_APPLICABLE')).toBe(false);
    expect(translationRefetchInterval(undefined)).toBe(false);
  });
});

describe('translationDisabledReason', () => {
  it('상태별 툴팁 문구를 아트보드 그대로 돌려준다', () => {
    expect(translationDisabledReason('NOT_APPLICABLE', false)).toBe('한국어 논문은 번역하지 않습니다');
    expect(translationDisabledReason('PENDING', false)).toBe('번역을 준비하고 있습니다');
    expect(translationDisabledReason('FAILED', false)).toBe('번역 생성에 실패했습니다');
  });

  it('READY인데 번역 블록이 없으면 준비 안 됨 문구다', () => {
    expect(translationDisabledReason('READY', false)).toBe('이 논문은 번역이 준비되지 않았습니다');
    expect(translationDisabledReason(undefined, false)).toBe('이 논문은 번역이 준비되지 않았습니다');
  });
});
```

`fe/src/markdown/paperContent.test.ts`의 `res` 헬퍼에 `translationStatus: 'READY'`를 기본값으로 넣고, 테스트 하나 추가:

```ts
  it('translationStatus를 그대로 넘긴다', () => {
    expect(adaptPaperContent(res({ translationStatus: 'PENDING' })).translationStatus).toBe('PENDING');
  });
```

- [ ] **Step 2: 실패 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test -- src/routes/study/translationStatus.test.ts src/markdown/paperContent.test.ts 2>&1 | tail -15
```

Expected: 모듈 없음·타입 오류로 실패.

- [ ] **Step 3: 타입 추가**

`fe/src/api/types.ts`의 `PaperStatus` 선언 뒤:

```ts
// 전체 번역 준비 상태. 파싱 status와 별개다 — 파싱이 COMPLETED인 채로 번역만 뒤따라 바뀐다.
export type TranslationStatus = 'NOT_APPLICABLE' | 'PENDING' | 'READY' | 'FAILED';
```

`PaperStatusResponse`:

```ts
export interface PaperStatusResponse {
  paperId: string;
  status: PaperStatus;
  translationStatus: TranslationStatus;
  updatedAt: string;
}
```

`PaperContentResponse`의 `sourceLanguage` 뒤에 `translationStatus: TranslationStatus;`.

- [ ] **Step 4: 본문 모델에 전달**

`fe/src/markdown/paperContent.ts`: import에 `TranslationStatus` 추가. `PaperContent`에 `translationStatus: TranslationStatus;` 추가(`sourceLanguage` 뒤). `adaptPaperContent`의 return에 `translationStatus: res.translationStatus` 추가.

- [ ] **Step 5: 헬퍼 작성**

`fe/src/routes/study/translationStatus.ts`:

```ts
// 학습 화면의 번역 상태 처리. 폴링은 PENDING일 때만, 툴팁은 아트보드 문구 그대로.
import type { TranslationStatus } from '../../api/types';

/** 컴파일은 분 단위라 서재의 2초 폴링보다 느슨하게 둔다. */
export const TRANSLATION_POLL_MS = 5000;

export function translationRefetchInterval(status: TranslationStatus | undefined): number | false {
  return status === 'PENDING' ? TRANSLATION_POLL_MS : false;
}

const REASON: Partial<Record<TranslationStatus, string>> = {
  NOT_APPLICABLE: '한국어 논문은 번역하지 않습니다',
  PENDING: '번역을 준비하고 있습니다',
  FAILED: '번역 생성에 실패했습니다',
};

/** 번역 버튼 비활성 사유. READY인데 번역 블록이 없는 경우(사이드카 없음·병합 0건)는 준비 안 됨 문구다. */
export function translationDisabledReason(status: TranslationStatus | undefined, hasTranslation: boolean): string {
  if (status === 'READY' && hasTranslation) return '';
  return (status && REASON[status]) ?? '이 논문은 번역이 준비되지 않았습니다';
}
```

- [ ] **Step 6: 테스트·타입 검사**

```bash
npm test -- src/routes/study/translationStatus.test.ts src/markdown/paperContent.test.ts 2>&1 | tail -8 && npm run typecheck 2>&1 | tail -15
```

Expected: 테스트 통과. `typecheck`는 `StudyPage.test.tsx`의 `getStatus` mock과 `contentResponse` 헬퍼에 `translationStatus`가 없어 실패한다 — Task 14에서 고치므로 여기서는 그 오류만 남아 있는지 확인한다.

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add fe/src/api/types.ts fe/src/markdown/paperContent.ts fe/src/markdown/paperContent.test.ts fe/src/routes/study/translationStatus.ts fe/src/routes/study/translationStatus.test.ts
git commit -m "[YMC-389] feat(fe): translationStatus 타입과 번역 상태 헬퍼"
```

---

## Task 14: FE — 학습 화면 폴링·재조회·툴팁

**Files:**
- Modify: `fe/src/routes/StudyPage.tsx`
- Test: `fe/src/routes/StudyPage.test.tsx`

**Interfaces:**
- Consumes: Task 13의 헬퍼와 타입.

- [ ] **Step 1: 테스트 헬퍼·기존 테스트를 새 응답에 맞춘다**

`StudyPage.test.tsx`:

- `contentResponse`에 세 번째 인자를 추가한다:

```ts
function contentResponse(
  translated: boolean,
  sourceLanguage: string | null = 'en',
  translationStatus: TranslationStatus = translated ? 'READY' : 'FAILED',
): PaperContentResponse {
  return {
    paperId: 'p1',
    title: '제목',
    sourceLanguage,
    translationStatus,
    schemaVersion: 1,
    blocks: [ /* 기존 그대로 */ ],
    assets: {},
  };
}
```

import에 `TranslationStatus` 추가(`import type { PaperContentResponse, TranslationStatus } from '../api/types';`).

- 상태 응답 헬퍼를 추가하고 `beforeEach`의 `getStatus` mock을 바꾼다:

```ts
function statusResponse(translationStatus: TranslationStatus = 'READY') {
  return { paperId: 'p1', status: 'COMPLETED' as const, translationStatus, updatedAt: '2026-09-09T00:00:00Z' };
}
```

`vi.mocked(getStatus).mockResolvedValue(statusResponse());`

- `번역이_없는_논문은_버튼이_비활성이고...` 테스트: `getStatus`를 NOT_APPLICABLE로 두도록 첫 줄에 `vi.mocked(getStatus).mockResolvedValue(statusResponse('NOT_APPLICABLE'));`를 추가한다(툴팁이 언어가 아니라 상태에서 나온다).
- `번역 없는 논문의 툴팁은 언어가 ko가 아니면 준비 안 됨 문구다`를 삭제하고 아래 describe로 대체한다.

- [ ] **Step 2: 실패하는 테스트 작성**

`StudyPage.test.tsx`에 describe 추가:

```ts
describe('StudyPage — 번역 상태', () => {
  it('PENDING이면 버튼이 비활성이고 준비 중 툴팁이다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('PENDING'));
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(false, 'en', 'PENDING'));
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());
    expect(modeButton().disabled).toBe(true);
    expect(modeButton().title).toBe('번역을 준비하고 있습니다');
  });

  it('FAILED이면 버튼이 비활성이고 실패 툴팁이다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('FAILED'));
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(false, 'en', 'FAILED'));
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());
    expect(modeButton().disabled).toBe(true);
    expect(modeButton().title).toBe('번역 생성에 실패했습니다');
  });

  it('READY인데 번역 블록이 없으면 준비 안 됨 툴팁이다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('READY'));
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(false, 'en', 'READY'));
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());
    expect(modeButton().disabled).toBe(true);
    expect(modeButton().title).toBe('이 논문은 번역이 준비되지 않았습니다');
  });

  it('PENDING → READY가 되면 본문을 다시 받아 번역 버튼이 활성된다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(getStatus)
        .mockResolvedValueOnce(statusResponse('PENDING'))
        .mockResolvedValue(statusResponse('READY'));
      vi.mocked(fetchPaperContent)
        .mockResolvedValueOnce(contentResponse(false, 'en', 'PENDING'))
        .mockResolvedValue(contentResponse(true, 'en', 'READY'));
      renderStudy();
      await waitFor(() => expect(modeButton()).toBeTruthy());
      expect(modeButton().disabled).toBe(true);

      await vi.advanceTimersByTimeAsync(5000);

      await waitFor(() => expect(fetchPaperContent).toHaveBeenCalledTimes(2));
      await waitFor(() => expect(modeButton().disabled).toBe(false));
      expect(getStatus).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});
```

- [ ] **Step 3: 실패 확인**

```bash
npm test -- src/routes/StudyPage.test.tsx 2>&1 | tail -20
```

Expected: 새 describe 4개 실패(툴팁 문구·활성 전환).

- [ ] **Step 4: `StudyPage` 수정**

- import 추가:

```ts
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { TranslationStatus } from '../api/types';
import { translationRefetchInterval, translationDisabledReason } from './study/translationStatus';
```

(`useQuery` import 줄을 위처럼 바꾼다.)

- `StudyPage`의 `statusQuery`:

```ts
  const statusQuery = useQuery({
    queryKey: ['paper-status', paperId],
    queryFn: () => getStatus(paperId as string),
    enabled: !!paperId,
    // 번역이 준비 중일 때만 폴링한다. 서재 폴링과 달리 파싱 상태는 이미 COMPLETED다.
    refetchInterval: (query) => translationRefetchInterval(query.state.data?.translationStatus),
  });
```

- `return <StudyPageContent paperId={paperId} />;` → `return <StudyPageContent paperId={paperId} translationStatus={statusQuery.data.translationStatus} />;`

- `StudyPageContent` 시그니처와 재조회 effect:

```ts
function StudyPageContent({ paperId, translationStatus }: { paperId: string; translationStatus: TranslationStatus }) {
  const queryClient = useQueryClient();
  const contentQuery = useQuery({
    queryKey: ['paper-content', paperId],
    queryFn: () => getPaperContent(paperId),
  });

  // READY로 바뀌는 순간 본문을 다시 받는다. 기존 데이터를 보여주다 새 데이터로 바뀌므로 화면이 비지 않는다.
  const prevTranslationStatus = useRef(translationStatus);
  useEffect(() => {
    if (prevTranslationStatus.current !== 'READY' && translationStatus === 'READY') {
      queryClient.invalidateQueries({ queryKey: ['paper-content', paperId] });
    }
    prevTranslationStatus.current = translationStatus;
  }, [translationStatus, paperId, queryClient]);
```

- 버튼 활성 조건과 툴팁(기존 `hasTranslation`·`effectiveMode` 부분):

```ts
  const hasTranslation = contentQuery.data?.hasTranslation ?? false;
  const translationEnabled = translationStatus === 'READY' && hasTranslation;
  // 저장된 선호값은 그대로 두고, 번역을 쓸 수 없는 논문에서는 off로만 적용한다.
  const effectiveMode: TranslationMode = translationEnabled ? translationMode : 'off';
```

`TranslationModeButton`:

```tsx
          <TranslationModeButton
            mode={effectiveMode}
            disabled={!translationEnabled}
            disabledReason={translationDisabledReason(translationStatus, hasTranslation)}
            onCycle={handleCycleTranslation}
          />
```

- [ ] **Step 5: 테스트·타입·빌드**

```bash
npm test 2>&1 | tail -8 && npm run typecheck 2>&1 | tail -5 && npm run build 2>&1 | tail -3
```

Expected: 전부 통과. `PENDING → READY` 테스트가 타이머 때문에 불안정하면 `advanceTimersByTimeAsync(5000)`을 `await vi.advanceTimersByTimeAsync(5500)`로 늘려 본다. 그래도 불안정하면 그 테스트는 `getStatus` 두 번째 호출까지만 검증하고 활성 전환은 `translationDisabledReason` 단위 테스트에 맡긴다(이유를 테스트 주석에 적는다).

- [ ] **Step 6: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add fe/src/routes/StudyPage.tsx fe/src/routes/StudyPage.test.tsx
git commit -m "[YMC-389] feat(fe): 번역 준비 중 폴링과 READY 전환 시 본문 재조회"
```

---

## Task 15: 마무리 — 전체 검증과 PR 준비

**Files:**
- 없음(검증과 문서)

- [ ] **Step 1: BE 전체 테스트**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test 2>&1 | tail -8
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: FE 전체 검증**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test 2>&1 | tail -5 && npm run typecheck && npm run build 2>&1 | tail -3
```

Expected: 전부 통과.

- [ ] **Step 3: 로컬 E2E(선택, Docker·LocalStack 필요)**

`infra/local/up.sh`로 스택을 올리고 BE(`./gradlew bootRun`)를 띄운 뒤, 영어 PDF를 등록하고 파싱 결과를 `infra/local/publish-parse-result.sh <paperId> COMPLETED <manifestKey>`로 넣는다. `knowledge-compile-requests` 큐에 요청이 쌓이는지 `awslocal sqs receive-message`로 확인하고, `/api/papers/{id}/status`가 `PENDING`인지 본다. 컴파일 워커가 로컬에 없으면 결과를 손으로 넣는다:

```bash
aws --endpoint-url http://localhost:4566 sqs send-message \
  --queue-url "$(aws --endpoint-url http://localhost:4566 sqs get-queue-url --queue-name knowledge-compile-results --query QueueUrl --output text)" \
  --message-body '{"paper_id":"<paperId>","status":"completed","message":"manual","manifest_key":"<manifestKey>"}'
```

사이드카가 S3에 없으면 WARN 후 COMPLETED가 되는지, 있으면 `textKor`가 붙는지 확인한다.

- [ ] **Step 4: 변경 요약을 사용자에게 보여준다**

`git log --oneline origin/main..HEAD`와 핵심 diff(Document 컬럼, ParseResultService 훅, 리스너, StudyPage)를 요약해 보여주고 푸시·PR 승인을 받는다. PR 본문(app):

```
## 배경
ai#25부터 번역이 파싱이 아니라 지식 컴파일 산출물(사이드카)로 온다. BE가 컴파일을 요청·수신하지 않아 dev 신규 영어 논문에 전체 번역이 없다. 설계: docs/superpowers/specs/2026-09-16-knowledge-compile-translation-design.md

## 변경사항
- document에 source_language·compile_status·compile_error_code. 응답 translationStatus는 언어×컴파일 상태로 계산
- 파싱 완료·적재 뒤 knowledge-compile-requests 발행(CAS 선점, 실패 시 반납·재전달)
- knowledge-compile-results 리스너. completed는 사이드카를 텍스트 블록에 병합, failed는 코드만 기록
- 리더의 파서 text_kor 경로 제거
- FE: PENDING일 때만 5초 폴링, READY면 본문 재조회, 툴팁 세 가지

**판단**
- null compile_status는 PENDING으로 응답한다. 발행 실패 재시도 창에서 FE 폴링이 끊기지 않게.
- 사이드카가 없거나 병합 0건이어도 COMPLETED. FE가 READY라도 번역 블록이 없으면 버튼을 닫는다.
- REQUESTED 커밋 뒤 발행 전 크래시는 파싱과 같은 창으로 두고 정체 스윕 후속에 포함.

## 검증
- BE ./gradlew test, FE npm test·typecheck·build 통과
- dev 배포 뒤: be/docs/db/document.sql의 dev 1회 SQL 실행 필요(legacy 논문 COMPLETED/FAILED 채우기)
- 미검증: dev 실환경 PENDING→READY 전환(컴파일 워커 연동). infra PR apply·CD 뒤 확인

## 의존
- project-docs#<번호>(계약), infra#<번호>(BE env)
```

푸시·PR 생성·머지는 각각 사용자 승인 뒤에 한다.

---

## Self-Review 결과

- 스펙 §3 계약 → Task 1·2. §4.1 스키마·SQL → Task 4. §4.2 도메인·CAS → Task 4·5. §4.3 설정 → Task 6. §4.4 발행 → Task 7. §4.5 수신·병합 → Task 9·11. §4.6 리더 정리·`readTranslations` → Task 8·10. §4.7 응답 → Task 12. §5 FE → Task 13·14. §6 인프라 → Task 3. §7 테스트 → 각 Task에 분산(발행 실패 재전달은 Task 7, 사이드카 없음은 Task 9·11). §8 배포 순서 → Task 15.
- 스펙 §7의 "모르는 error code는 계약 위반" 항목은 기존 `ParseResultMessage`와 같은 이유(새 코드가 늘어도 결과를 잃지 않음)로 위반이 아니라 저장으로 바꿨다(Task 11 record 주석). 스펙 §4.5에 이 결정을 반영한다.
- 스펙 §4.4의 "정체 정리 스윕 티켓"은 이미 `StalePaperCleanup`이 있으므로, 후속은 새 스케줄러가 아니라 그 클래스에 REQUESTED 정체 처리를 더하는 일이다. 스펙 문구를 그렇게 고친다.
- 이름 일관성: `KnowledgeCompileStarter.startIfCompleted`, `KnowledgeCompileResultService.apply`, `DocumentTranslationMergeService.merge`, `PaperPackageReader.readTranslations`, `DocumentContentBlock.mergeTranslation`, `DocumentTransitions.markCompileRequested/revertCompileRequested/markCompiled`, FE `translationRefetchInterval/translationDisabledReason` — 모든 Task에서 같은 이름을 쓴다.
