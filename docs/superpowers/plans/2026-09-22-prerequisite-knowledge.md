# FT-012 선행지식 하이라이트·설명 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** compile 완료 때 S3 하이라이트 sidecar를 PostgreSQL에 적재하고, 본문 응답에 투영하며, 하이라이트를 누르면 Valkey 30일 캐시를 거쳐 AI non-streaming으로 영문·국문 설명을 만들어 Design v3 팝오버로 보여준다.

**Architecture:** BE는 `paper` 컨텍스트 안에 자식 테이블 `document_prerequisite_highlight`와 설명 서비스를 두고, Valkey와 AI는 포트(`service/port`)·구현(`infra`)으로 분리한다. FE는 기존 `rehypeSourcePos` 앞에 하이라이트 rehype 플러그인을 넣고, 뷰어 오버레이 형제로 `PrerequisiteLayer`를 추가하며, 열림 상태는 `StudyPage`가 조율한다.

**Tech Stack:** Spring Boot 3.5.15, Spring Data JPA, Flyway, Spring Data Redis(Lettuce, `StringRedisTemplate`), WebFlux `WebClient`(기존 `aiWebClient` 빈), Micrometer, Testcontainers(PostgreSQL·LocalStack·Valkey), React 19, react-markdown + hast, vitest + RTL.

**Spec:** `docs/superpowers/specs/2026-09-22-prerequisite-knowledge-design.md`

## Global Constraints

- 기존 BE↔AI 계약을 그대로 쓴다. 새 계약을 만들지 않는다. AI 주소 `POST /api/v1/agents/prerequisite-knowledge-agent/runs`, anchor 필드명은 `block_id`·`offset`.
- S3 sidecar는 compile 완료 때 한 번만 읽는다. 본문 조회·설명 요청은 DB와 Valkey만 본다.
- 캐시 키 `prerequisite-definition:v1:{documentId}:{generatorVersion}:{normalizedTermHash}`. 정규화는 NFC, trim, 연속 공백 한 칸, `Locale.ROOT` 소문자. 해시 SHA-256 hex.
- `PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT`는 기본값 없음. `PREREQUISITE_DEFINITION_CACHE_TTL` 기본 30일.
- AI 타임아웃 `ai.prerequisite-definition-timeout` 기본 60초. 잠금 만료 = 타임아웃 + 5초.
- `UsageService`를 호출하지 않는다.
- 에러 코드는 계약에 있는 것만: `PREREQUISITE_HIGHLIGHT_NOT_FOUND`(404), `PREREQUISITE_NOT_READY`(409), `PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED`(429), `PREREQUISITE_DEFINITION_FAILED`(502).
- BE 규칙(`be/CLAUDE.md`): 엔티티 `@Getter`만, 빈은 `@RequiredArgsConstructor`, 컨텍스트 간 ID 참조, 포트는 외부 시스템에만.
- 커밋 형식 `[YMC-414] type(scope): subject`. 코드 주석은 1~2줄, 티켓·문서 인용 금지.
- 모든 Gradle 명령은 `be/`에서 `./gradlew`, FE는 `fe/`에서 `npm test -- --run <file>`.

## 작업 현황

worktree `.worktrees/app-ymc414`에 Task 1의 일부가 이미 있다(커밋 전): `ParsedPrerequisiteHighlight`, `PaperPackageReader.readPrerequisiteHighlights`, `S3PaperPackageReader` 구현과 테스트 6개(통과), `V2` 마이그레이션, `DocumentPrerequisiteHighlight` 엔티티, fixture `prerequisite-highlights.json`, `IntegrationTest` 수정, `DocumentPrerequisiteHighlightIngestIntegrationTest`(리포지토리·서비스 없어 컴파일 불가). Task 1은 그 상태에서 이어 간다.

---

### Task 1: 하이라이트 저장과 compile 적재 (BE)

**Files:**
- Modify: `be/src/main/resources/db/migration/V2__document_prerequisite_highlight.sql` (check 제약 추가)
- Modify: `be/src/main/java/com/ymc/paper/domain/DocumentPrerequisiteHighlight.java` (있음)
- Create: `be/src/main/java/com/ymc/paper/domain/DocumentPrerequisiteHighlightRepository.java`
- Create: `be/src/main/java/com/ymc/paper/service/DocumentPrerequisiteHighlightIngestService.java`
- Modify: `be/src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java:35-70`
- Test: `be/src/test/java/com/ymc/paper/service/DocumentPrerequisiteHighlightIngestIntegrationTest.java` (있음, 케이스 1개 추가)
- Test: `be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultConsumptionIntegrationTest.java`

**Interfaces:**
- Consumes: `PaperPackageReader.readPrerequisiteHighlights(String manifestKey): List<ParsedPrerequisiteHighlight>` (있음), `DocumentContentBlockRepository.findAllByDocumentIdOrderByGlobalOrderAsc`, `DocumentRepository.findWithLockByRequestPaperId`.
- Produces: `DocumentPrerequisiteHighlightRepository` — `List<DocumentPrerequisiteHighlight> findAllByDocumentIdOrderByIdAsc(UUID)`, `Optional<DocumentPrerequisiteHighlight> findByDocumentIdAndHighlightId(UUID, String)`, `void deleteByDocumentId(UUID)`. `DocumentPrerequisiteHighlightIngestService.ingest(UUID documentId, String manifestKey): int`.

- [ ] **Step 1: 마이그레이션에 check 제약 추가**

`V2__document_prerequisite_highlight.sql`를 아래로 교체한다.

```sql
create table document_prerequisite_highlight (
    id           bigserial    not null,
    document_id  uuid         not null,
    highlight_id varchar(64)  not null,
    block_id     varchar(255) not null,
    start_offset integer      not null,
    end_offset   integer      not null,
    text         text         not null,
    primary key (id),
    constraint uk_document_prerequisite_highlight unique (document_id, highlight_id),
    constraint ck_document_prerequisite_highlight_range check (start_offset >= 0 and start_offset < end_offset)
);
```

- [ ] **Step 2: 리포지토리 작성**

```java
package com.ymc.paper.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DocumentPrerequisiteHighlightRepository extends JpaRepository<DocumentPrerequisiteHighlight, Long> {

    /** 적재 순서가 곧 문서 순서다 — 사이드카가 문서 순서로 정렬돼 온다. */
    List<DocumentPrerequisiteHighlight> findAllByDocumentIdOrderByIdAsc(UUID documentId);

    Optional<DocumentPrerequisiteHighlight> findByDocumentIdAndHighlightId(UUID documentId, String highlightId);

    @Modifying
    @Query("delete from DocumentPrerequisiteHighlight h where h.documentId = :documentId")
    void deleteByDocumentId(UUID documentId);
}
```

- [ ] **Step 3: 글자 불일치 테스트 추가**

`DocumentPrerequisiteHighlightIngestIntegrationTest`에 추가. fixture 블록 `p0000-b0002`의 원문은 `Body paragraph in English.`다.

```java
    @Test
    void 본문_글자와_다른_하이라이트는_건너뛰고_WARN이다(CapturedOutput output) {
        Paper paper = givenIngestedPaper();
        putSidecar(paper, """
                {"schema_version":1,"offset_encoding":"utf-16","highlights":[
                  {"highlight_id":"prerequisite-0001","block_id":"p0000-b0002","start_offset":0,"end_offset":4,"text":"Para"},
                  {"highlight_id":"prerequisite-0002","block_id":"p0000-b0002","start_offset":5,"end_offset":14,"text":"paragraph"}
                ]}
                """);

        int ingested = ingestService.ingest(paper.getDocumentId(), "papers/" + paper.getId() + "/manifest.json");

        assertThat(ingested).isEqualTo(1);
        assertThat(highlightsOf(paper.getDocumentId()))
                .extracting(DocumentPrerequisiteHighlight::getHighlightId).containsExactly("prerequisite-0002");
        assertThat(output.getOut()).contains("prerequisite-0001");
    }
```

- [ ] **Step 4: 서비스 stub으로 실패 확인**

```java
package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentPrerequisiteHighlightIngestService {

    @Transactional
    public int ingest(UUID documentId, String manifestKey) {
        return 0;
    }
}
```

Run: `./gradlew test --tests 'com.ymc.paper.service.DocumentPrerequisiteHighlightIngestIntegrationTest'`
Expected: 5 tests, 4 FAIL (`사이드카가_없는_패키지는_0건이다`만 통과).

- [ ] **Step 5: 서비스 구현**

```java
package com.ymc.paper.service;

import java.util.ArrayList;
import java.util.List;
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
import com.ymc.paper.domain.DocumentPrerequisiteHighlight;
import com.ymc.paper.domain.DocumentPrerequisiteHighlightRepository;
import com.ymc.paper.service.port.PaperPackageReader;
import com.ymc.paper.service.port.ParsedPrerequisiteHighlight;

import lombok.RequiredArgsConstructor;

/**
 * 컴파일 사이드카의 선행지식 범위를 Document 단위로 적재한다. 적재된 블록의 원문을 잘라 text와 대조하고,
 * 어긋나는 항목만 건너뛴다. 재적재는 기존 행을 지우고 다시 넣는다.
 */
@Service
@RequiredArgsConstructor
public class DocumentPrerequisiteHighlightIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPrerequisiteHighlightIngestService.class);

    private final PaperPackageReader packageReader;
    private final DocumentContentBlockRepository blockRepository;
    private final DocumentPrerequisiteHighlightRepository highlightRepository;

    /** @return 적재한 하이라이트 수. 사이드카가 없거나 형식이 어긋나면 0. */
    @Transactional
    public int ingest(UUID documentId, String manifestKey) {
        List<ParsedPrerequisiteHighlight> parsed = packageReader.readPrerequisiteHighlights(manifestKey);
        Map<String, DocumentContentBlock> byId = blockRepository
                .findAllByDocumentIdOrderByGlobalOrderAsc(documentId).stream()
                .collect(Collectors.toMap(DocumentContentBlock::getBlockId, Function.identity()));

        List<DocumentPrerequisiteHighlight> rows = new ArrayList<>();
        for (ParsedPrerequisiteHighlight h : parsed) {
            DocumentContentBlock block = byId.get(h.blockId());
            if (block == null) {
                log.warn("사이드카에만 있는 blockId, 건너뜀: highlightId={}, blockId={}, documentId={}",
                        h.highlightId(), h.blockId(), documentId);
                continue;
            }
            String text = block.getContent().path("text").asText(null);
            if (text == null || h.endOffset() > text.length()
                    || !text.substring(h.startOffset(), h.endOffset()).equals(h.text())) {
                log.warn("본문 범위와 text가 다른 하이라이트, 건너뜀: highlightId={}, blockId={}, documentId={}",
                        h.highlightId(), h.blockId(), documentId);
                continue;
            }
            rows.add(DocumentPrerequisiteHighlight.of(
                    documentId, h.highlightId(), h.blockId(), h.startOffset(), h.endOffset(), h.text()));
        }
        highlightRepository.deleteByDocumentId(documentId);
        highlightRepository.saveAll(rows);
        return rows.size();
    }
}
```

- [ ] **Step 6: 서비스 테스트 통과 확인**

Run: `./gradlew test --tests 'com.ymc.paper.service.DocumentPrerequisiteHighlightIngestIntegrationTest'`
Expected: 5 PASS.

- [ ] **Step 7: compile 결과 통합 테스트 추가**

`KnowledgeCompileResultConsumptionIntegrationTest`에 추가. 기존 `givenRequestedPaper`·`publishCompileResult`·`awaitCompileStatus`·`documentOf`를 쓴다.

```java
    @Test
    @DisplayName("completed: 선행지식 사이드카를 Document 단위로 적재한다")
    void completedIngestsPrerequisiteHighlights() {
        Paper paper = givenRequestedPaper("compile-highlight.pdf");

        publishCompileResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKeyOf(paper)));
        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);

        assertThat(documentPrerequisiteHighlightRepository
                .findAllByDocumentIdOrderByIdAsc(paper.getDocumentId()))
                .extracting(DocumentPrerequisiteHighlight::getHighlightId)
                .containsExactly("prerequisite-0001", "prerequisite-0002");
    }

    @Test
    @DisplayName("completed 동시 2건: 예외 없이 하이라이트 1세트만 남는다")
    void concurrentCompletedIngestsOnce() throws Exception {
        Paper paper = givenRequestedPaper("compile-dup.pdf");
        String message = """
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKeyOf(paper));

        publishCompileResult(message);
        publishCompileResult(message);
        awaitCompileStatus(paper.getId(), CompileStatus.COMPLETED);
        Thread.sleep(1000); // 두 번째 메시지 처리까지 기다린다

        assertThat(documentPrerequisiteHighlightRepository
                .findAllByDocumentIdOrderByIdAsc(paper.getDocumentId())).hasSize(2);
    }
```

import에 `com.ymc.paper.domain.DocumentPrerequisiteHighlight` 추가.

- [ ] **Step 8: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileResultConsumptionIntegrationTest'`
Expected: 새 테스트 2개 FAIL (하이라이트 0건).

- [ ] **Step 9: compile 결과 서비스에 적재와 잠금 추가**

`KnowledgeCompileResultService`를 수정한다. 필드에 `private final DocumentPrerequisiteHighlightIngestService highlightIngestService;`를 추가하고, `apply` 첫 줄의 `findByRequestPaperId`를 `findWithLockByRequestPaperId`로 바꾼다. COMPLETED 분기는 아래로 교체한다.

```java
        if (terminal == CompileStatus.COMPLETED) {
            int merged = mergeService.merge(documentId, manifestKey);
            if (merged == 0 && "en".equals(document.getSourceLanguage())) {
                log.warn("영어 문서인데 병합된 번역이 없습니다, READY로 응답되지만 번역 블록 없음: requestPaperId={}, "
                        + "documentId={}, manifestKey={}", requestPaperId, documentId, manifestKey);
            }
            int highlights = highlightIngestService.ingest(documentId, manifestKey);
            // manifest를 한 번 더 읽는다. 재파싱 경로가 없어 두 읽기 사이에 manifest가 바뀌지 않는다.
            String knowledgeGraphKey = packageReader.readKnowledgeGraphKey(manifestKey).orElse(null);
            transitions.markCompiled(documentId, CompileStatus.COMPLETED, null, knowledgeGraphKey);
            log.info("컴파일 완료 반영: requestPaperId={}, documentId={}, mergedBlocks={}, highlights={}, "
                    + "knowledgeGraphKey={}", requestPaperId, documentId, merged, highlights, knowledgeGraphKey);
            return;
        }
```

클래스 주석 첫 줄을 `컴파일 결과 반영. 병합·하이라이트 적재와 상태 종결을 한 트랜잭션으로 묶고 Document 행을 잠가 재전달을 직렬화한다.`로 바꾼다.

- [ ] **Step 10: 통과 확인과 전체 회귀**

Run: `./gradlew test --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileResultConsumptionIntegrationTest' --tests 'com.ymc.FlywayMigrationTest'`
Expected: 모두 PASS.
Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: 커밋**

```bash
git add be/src/main/resources/db/migration/V2__document_prerequisite_highlight.sql \
  be/src/main/java/com/ymc/paper/domain/DocumentPrerequisiteHighlight.java \
  be/src/main/java/com/ymc/paper/domain/DocumentPrerequisiteHighlightRepository.java \
  be/src/main/java/com/ymc/paper/service/DocumentPrerequisiteHighlightIngestService.java \
  be/src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java \
  be/src/main/java/com/ymc/paper/service/port/ParsedPrerequisiteHighlight.java \
  be/src/main/java/com/ymc/paper/service/port/PaperPackageReader.java \
  be/src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java \
  be/src/test/java/com/ymc/paper/infra/parsing/S3PaperPackageReaderTest.java \
  be/src/test/java/com/ymc/paper/service/DocumentPrerequisiteHighlightIngestIntegrationTest.java \
  be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultConsumptionIntegrationTest.java \
  be/src/test/java/com/ymc/support/IntegrationTest.java \
  be/src/test/resources/fixtures/paper-package-translated/manifest.json \
  be/src/test/resources/fixtures/paper-package-translated/frontend/prerequisite-highlights.json
git commit -m "[YMC-414] feat(be): 선행지식 하이라이트 sidecar를 compile 완료 시 Document 단위로 적재"
```

---

### Task 2: 기존 Document 일회성 채우기 (BE)

**Files:**
- Create: `be/src/main/java/com/ymc/paper/service/PrerequisiteHighlightBackfill.java`
- Modify: `be/src/main/java/com/ymc/paper/domain/DocumentRepository.java` (쿼리 1개 추가)
- Modify: `be/src/main/resources/application.yml` (`prerequisite.backfill.enabled: false`)
- Test: `be/src/test/java/com/ymc/paper/service/PrerequisiteHighlightBackfillIntegrationTest.java`

**Interfaces:**
- Consumes: `DocumentPrerequisiteHighlightIngestService.ingest(UUID, String)`.
- Produces: `DocumentRepository.findAllCompiledWithoutPrerequisiteHighlights(): List<Document>`, `PrerequisiteHighlightBackfill.run(): int`.

- [ ] **Step 1: 테스트 작성**

```java
package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PrerequisiteHighlightBackfillIntegrationTest extends IntegrationTest {

    @Autowired
    PrerequisiteHighlightBackfill backfill;

    private Paper givenCompiledWithoutHighlights(String filename, boolean sidecarOnS3) {
        Paper paper = givenProcessingPaper(filename);
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = sidecarOnS3 ? givenTranslatedPackageOnS3(paper.getId()) : givenPackageOnS3(paper.getId());
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null, null);
        return reload(paper.getId());
    }

    @Test
    void 컴파일_완료인데_하이라이트가_없는_Document만_채운다() {
        Paper withSidecar = givenCompiledWithoutHighlights("bf-with.pdf", true);
        Paper withoutSidecar = givenCompiledWithoutHighlights("bf-without.pdf", false);

        int filled = backfill.run();

        assertThat(filled).isEqualTo(1);
        assertThat(documentPrerequisiteHighlightRepository
                .findAllByDocumentIdOrderByIdAsc(withSidecar.getDocumentId())).hasSize(2);
        assertThat(documentPrerequisiteHighlightRepository
                .findAllByDocumentIdOrderByIdAsc(withoutSidecar.getDocumentId())).isEmpty();
    }

    @Test
    void 두_번_돌려도_이미_채운_Document는_건드리지_않는다() {
        givenCompiledWithoutHighlights("bf-twice.pdf", true);
        backfill.run();

        assertThat(backfill.run()).isZero();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.service.PrerequisiteHighlightBackfillIntegrationTest'`
Expected: 컴파일 실패 (`PrerequisiteHighlightBackfill` 없음).

- [ ] **Step 3: 리포지토리 쿼리와 채우기 구현**

`DocumentRepository`에 추가:

```java
    /** 컴파일은 끝났는데 선행지식 행이 없는 Document. 일회성 채우기 대상이다. */
    @Query("select d from Document d where d.compileStatus = com.ymc.paper.domain.CompileStatus.COMPLETED "
            + "and not exists (select 1 from DocumentPrerequisiteHighlight h where h.documentId = d.id)")
    List<Document> findAllCompiledWithoutPrerequisiteHighlights();
```

`PrerequisiteHighlightBackfill`:

```java
package com.ymc.paper.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 선행지식 적재 도입 전에 컴파일된 Document를 한 번 채운다. 사이드카가 없는 Document는 0건으로 남고
 * 다음 실행에서 다시 대상이 되지만 결과는 같다. dev에서 한 번 돌린 뒤 제거한다.
 */
@Component
@RequiredArgsConstructor
public class PrerequisiteHighlightBackfill {

    private static final Logger log = LoggerFactory.getLogger(PrerequisiteHighlightBackfill.class);

    private final DocumentRepository documentRepository;
    private final DocumentPrerequisiteHighlightIngestService ingestService;

    @EventListener(ApplicationReadyEvent.class)
    @ConditionalOnProperty(name = "prerequisite.backfill.enabled", havingValue = "true")
    public void onReady() {
        run();
    }

    /** @return 하이라이트를 1건 이상 적재한 Document 수 */
    public int run() {
        List<Document> targets = documentRepository.findAllCompiledWithoutPrerequisiteHighlights();
        int filled = 0;
        for (Document document : targets) {
            String manifestKey = "papers/" + document.getRequestPaperId() + "/manifest.json";
            try {
                if (ingestService.ingest(document.getId(), manifestKey) > 0) {
                    filled++;
                }
            } catch (RuntimeException e) {
                log.warn("선행지식 채우기 실패, 다음 Document로: documentId={}", document.getId(), e);
            }
        }
        log.info("선행지식 채우기 완료: 대상={}, 적재={}", targets.size(), filled);
        return filled;
    }
}
```

`@ConditionalOnProperty`는 메서드에 붙지 않으므로 클래스에 붙이면 테스트에서 빈이 없다. 대신 `onReady`에서 프로퍼티를 읽는다: 필드 `@Value("${prerequisite.backfill.enabled:false}") boolean enabled`를 두려면 `@RequiredArgsConstructor`와 충돌하므로, 생성자를 명시한다.

```java
    private final boolean enabled;

    public PrerequisiteHighlightBackfill(
            DocumentRepository documentRepository,
            DocumentPrerequisiteHighlightIngestService ingestService,
            @Value("${prerequisite.backfill.enabled:false}") boolean enabled) {
        this.documentRepository = documentRepository;
        this.ingestService = ingestService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled) {
            run();
        }
    }
```

`@RequiredArgsConstructor`와 `@ConditionalOnProperty` import는 제거하고 `org.springframework.beans.factory.annotation.Value`를 추가한다.

`application.yml`의 `paper:` 블록 앞에 추가:

```yaml
prerequisite:
  backfill:
    enabled: false                  # true면 기동 시 컴파일 완료 Document의 선행지식을 한 번 채운다
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.ymc.paper.service.PrerequisiteHighlightBackfillIntegrationTest'`
Expected: 2 PASS.

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/paper/service/PrerequisiteHighlightBackfill.java \
  be/src/main/java/com/ymc/paper/domain/DocumentRepository.java \
  be/src/main/resources/application.yml \
  be/src/test/java/com/ymc/paper/service/PrerequisiteHighlightBackfillIntegrationTest.java
git commit -m "[YMC-414] feat(be): 기존 컴파일 완료 Document의 선행지식 일회성 채우기"
```

---

### Task 3: 본문 조회 projection (BE)

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/PaperContentView.java`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperContentQueryService.java:46-83`
- Modify: `be/src/main/java/com/ymc/paper/api/dto/PaperContentResponse.java`
- Test: `be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java`

**Interfaces:**
- Produces: `PaperContentView.prerequisiteHighlights: List<PaperContentView.Highlight>`, `Highlight(String highlightId, String blockId, int startOffset, int endOffset, String text)`. 응답 JSON 필드 `prerequisiteHighlights`.

- [ ] **Step 1: 테스트 추가**

`PaperContentIntegrationTest`에 추가. `@Autowired DocumentPrerequisiteHighlightIngestService highlightIngestService;` 필드를 두고 `givenIngestedTranslatedPaper`의 `mergeService.merge(...)` 다음 줄에 `highlightIngestService.ingest(paper.getDocumentId(), manifestKey);`를 넣는다.

```java
    @Test
    void 컴파일_전에는_prerequisiteHighlights가_빈_배열이다() throws Exception {
        Paper paper = givenIngestedPaper();

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prerequisiteHighlights").isArray())
                .andExpect(jsonPath("$.prerequisiteHighlights").isEmpty());
    }

    @Test
    void 컴파일_뒤에는_적재한_하이라이트를_문서_순서로_담는다() throws Exception {
        Paper paper = givenIngestedTranslatedPaper();

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prerequisiteHighlights.length()").value(2))
                .andExpect(jsonPath("$.prerequisiteHighlights[0].highlightId").value("prerequisite-0001"))
                .andExpect(jsonPath("$.prerequisiteHighlights[0].blockId").value("p0000-b0001"))
                .andExpect(jsonPath("$.prerequisiteHighlights[0].startOffset").value(13))
                .andExpect(jsonPath("$.prerequisiteHighlights[0].endOffset").value(29))
                .andExpect(jsonPath("$.prerequisiteHighlights[0].text").value("new architecture"));
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperContentIntegrationTest'`
Expected: 새 테스트 2개 FAIL (`$.prerequisiteHighlights` 없음).

- [ ] **Step 3: 뷰·서비스·DTO 수정**

`PaperContentView`에 컴포넌트와 record 추가:

```java
        Map<String, Asset> assets,
        List<Highlight> prerequisiteHighlights) {

    public record Highlight(String highlightId, String blockId, int startOffset, int endOffset, String text) {
    }
```

`PaperContentQueryService`: 필드 `private final DocumentPrerequisiteHighlightRepository highlightRepository;` 추가. `return new PaperContentView(...)` 앞에:

```java
        List<PaperContentView.Highlight> highlights = highlightRepository
                .findAllByDocumentIdOrderByIdAsc(document.getId()).stream()
                .map(h -> new PaperContentView.Highlight(
                        h.getHighlightId(), h.getBlockId(), h.getStartOffset(), h.getEndOffset(), h.getText()))
                .toList();
```

생성자 호출 마지막 인자로 `highlights`를 넘긴다.

`PaperContentResponse`: 컴포넌트 `List<Highlight> prerequisiteHighlights` 추가, `from`에서 `view.prerequisiteHighlights().stream().map(h -> new Highlight(h.highlightId(), h.blockId(), h.startOffset(), h.endOffset(), h.text())).toList()`를 마지막 인자로. record 추가:

```java
    /** 계약 `PrerequisiteHighlight`. offset은 UTF-16 code unit, start 포함·end 제외. */
    public record Highlight(String highlightId, String blockId, int startOffset, int endOffset, String text) {
    }
```

`PaperContentView`를 생성하는 다른 곳이 있으면(`grep -rn "new PaperContentView(" be/src`) `List.of()`를 넘긴다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperContentIntegrationTest'`
Expected: 모두 PASS.

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/paper/service/PaperContentView.java \
  be/src/main/java/com/ymc/paper/service/PaperContentQueryService.java \
  be/src/main/java/com/ymc/paper/api/dto/PaperContentResponse.java \
  be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java
git commit -m "[YMC-414] feat(be): 본문 응답에 prerequisiteHighlights 투영"
```

---

### Task 4: 설명 API — 포트·AI 어댑터·서비스 (BE, 캐시 없이)

Valkey는 Task 5에서 붙인다. 이 Task에서는 캐시·잠금 포트를 **인메모리 구현 없이** 인터페이스만 선언하고, 서비스는 포트를 주입받되 통합 테스트는 `@MockitoBean`으로 포트를 대체한다.

**Files:**
- Modify: `be/src/main/java/com/ymc/common/error/ErrorCode.java`
- Create: `be/src/main/java/com/ymc/paper/service/port/PrerequisiteDefinitionGenerator.java`
- Create: `be/src/main/java/com/ymc/paper/service/port/PrerequisiteDefinitionCache.java`
- Create: `be/src/main/java/com/ymc/paper/service/port/PrerequisiteGenerationLock.java`
- Create: `be/src/main/java/com/ymc/paper/service/PrerequisiteDefinition.java`
- Create: `be/src/main/java/com/ymc/paper/service/PrerequisiteDefinitionMarkdown.java`
- Create: `be/src/main/java/com/ymc/paper/service/PrerequisiteDefinitionService.java`
- Create: `be/src/main/java/com/ymc/paper/service/PrerequisiteDefinitionMetrics.java`
- Create: `be/src/main/java/com/ymc/paper/infra/ai/AiPrerequisiteDefinitionAdapter.java`
- Create: `be/src/main/java/com/ymc/paper/infra/ai/PrerequisiteDefinitionProperties.java`
- Create: `be/src/main/java/com/ymc/paper/infra/ai/PrerequisiteDefinitionConfig.java`
- Create: `be/src/main/java/com/ymc/paper/api/dto/PrerequisiteDefinitionResponse.java`
- Modify: `be/src/main/java/com/ymc/paper/api/PaperController.java`
- Modify: `be/src/main/resources/application.yml`
- Test: `be/src/test/java/com/ymc/paper/service/PrerequisiteDefinitionMarkdownTest.java`
- Test: `be/src/test/java/com/ymc/support/FakeAiJsonServer.java`
- Test: `be/src/test/java/com/ymc/paper/infra/ai/AiPrerequisiteDefinitionAdapterTest.java`
- Test: `be/src/test/java/com/ymc/paper/api/PrerequisiteDefinitionIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record PrerequisiteDefinition(String definitionEn, String definitionKo)`
  - `PrerequisiteDefinitionGenerator.generate(String requestPaperId, String blockId, int startOffset, int endOffset): PrerequisiteDefinition` — 실패 시 `PrerequisiteDefinitionGenerator.GenerationFailedException` throw.
  - `PrerequisiteDefinitionCache.get(String key): Optional<PrerequisiteDefinition>`, `put(String key, PrerequisiteDefinition value)` — 장애 시 예외 대신 각각 empty·무시하고 WARN 남기는 것은 구현 책임.
  - `PrerequisiteGenerationLock.tryAcquire(UUID userId): Optional<String> token`, `release(UUID userId, String token)`. 획득 실패는 empty, Valkey 장애는 `LockUnavailableException`.
  - `PrerequisiteDefinitionService.define(UUID paperId, UUID ownerId, String highlightId): PrerequisiteDefinitionView` where `record PrerequisiteDefinitionView(String term, String definitionEn, String definitionKo)`.
  - `PrerequisiteDefinitionMarkdown.parse(String markdown): Optional<PrerequisiteDefinition>`.
  - `PrerequisiteDefinitionMetrics.hit()`, `.generated()`, `.failed()`, `.rejectedConcurrent()`, `.cacheError()`.

- [ ] **Step 1: 에러 코드 추가**

`ErrorCode`의 `KNOWLEDGE_GRAPH_NOT_READY(HttpStatus.CONFLICT)` 뒤에 (세미콜론 위치 조정):

```java
    KNOWLEDGE_GRAPH_NOT_READY(HttpStatus.CONFLICT),

    /** 현재 Document에 highlightId가 없음 */
    PREREQUISITE_HIGHLIGHT_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 본문 또는 compile sidecar가 아직 준비되지 않음 */
    PREREQUISITE_NOT_READY(HttpStatus.CONFLICT),

    /** 사용자의 다른 선행지식 설명이 생성 중 */
    PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),

    /** AI 설명 생성에 실패함 */
    PREREQUISITE_DEFINITION_FAILED(HttpStatus.BAD_GATEWAY);
```

- [ ] **Step 2: Markdown 파서 단위 테스트**

```java
package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrerequisiteDefinitionMarkdownTest {

    @Test
    void 고정_형식에서_영문과_국문을_꺼낸다() {
        String md = "### Self-attention\n\n**Definition (정의)**\n\n"
                + "A mechanism that relates each token to every other token  \n"
                + "각 토큰을 다른 모든 토큰과 연결하는 메커니즘";

        assertThat(PrerequisiteDefinitionMarkdown.parse(md)).contains(new PrerequisiteDefinition(
                "A mechanism that relates each token to every other token",
                "각 토큰을 다른 모든 토큰과 연결하는 메커니즘"));
    }

    @Test
    void 줄이_하나_더_붙으면_empty다() {
        String md = "### T\n\n**Definition (정의)**\n\nen  \nko\n\n추가 설명";
        assertThat(PrerequisiteDefinitionMarkdown.parse(md)).isEmpty();
    }

    @Test
    void 고정_문구가_다르면_empty다() {
        String md = "### T\n\n**Definition**\n\nen  \nko";
        assertThat(PrerequisiteDefinitionMarkdown.parse(md)).isEmpty();
    }

    @Test
    void null이나_빈_문자열은_empty다() {
        assertThat(PrerequisiteDefinitionMarkdown.parse(null)).isEmpty();
        assertThat(PrerequisiteDefinitionMarkdown.parse("")).isEmpty();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.service.PrerequisiteDefinitionMarkdownTest'`
Expected: 컴파일 실패.

- [ ] **Step 4: record와 파서 구현**

```java
package com.ymc.paper.service;

/** 생성된 선행지식 설명. 팝오버 제목(term)은 여기 없고 하이라이트 원문을 쓴다. */
public record PrerequisiteDefinition(String definitionEn, String definitionKo) {
}
```

```java
package com.ymc.paper.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI가 고정 형식으로 만드는 definition Markdown에서 영문·국문 한 줄씩 꺼낸다.
 * 전체 형식을 통째로 맞춰 형식이 바뀌면 조용히 틀린 값을 내지 않고 empty가 된다.
 */
public final class PrerequisiteDefinitionMarkdown {

    private static final Pattern FORMAT = Pattern.compile(
            "^### [^\\n]+\\n\\n\\*\\*Definition \\(정의\\)\\*\\*\\n\\n([^\\n]+?) {2}\\n([^\\n]+)$");

    private PrerequisiteDefinitionMarkdown() {
    }

    public static Optional<PrerequisiteDefinition> parse(String markdown) {
        if (markdown == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(markdown.strip().replace("\r\n", "\n"));
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new PrerequisiteDefinition(m.group(1).strip(), m.group(2).strip()));
    }
}
```

주의: `strip()`은 마지막 줄 뒤 개행만 제거한다. 영문 줄 끝의 공백 두 칸은 중간에 있으므로 유지된다.

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests 'com.ymc.paper.service.PrerequisiteDefinitionMarkdownTest'`
Expected: 4 PASS.

- [ ] **Step 6: 포트 3개와 메트릭·프로퍼티 작성**

```java
package com.ymc.paper.service.port;

import com.ymc.paper.service.PrerequisiteDefinition;

/** BE↔AI prerequisite-knowledge-agent-run(non-streaming) 호출. 구현: infra/ai. */
public interface PrerequisiteDefinitionGenerator {

    /**
     * @param requestPaperId AI가 아는 논문 id (document.requestPaperId)
     * @throws GenerationFailedException AI 4xx·5xx, 타임아웃, 응답 형식 오류
     */
    PrerequisiteDefinition generate(String requestPaperId, String blockId, int startOffset, int endOffset);

    class GenerationFailedException extends RuntimeException {
        public GenerationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

```java
package com.ymc.paper.service.port;

import java.util.Optional;

import com.ymc.paper.service.PrerequisiteDefinition;

/** 선행지식 설명 캐시. 장애는 예외로 올리지 않는다 — get은 empty, put은 무시하고 WARN. */
public interface PrerequisiteDefinitionCache {

    Optional<PrerequisiteDefinition> get(String key);

    void put(String key, PrerequisiteDefinition value);
}
```

```java
package com.ymc.paper.service.port;

import java.util.Optional;
import java.util.UUID;

/** 사용자당 설명 생성 1개를 지키는 소유권. */
public interface PrerequisiteGenerationLock {

    /** @return 획득하면 해제용 토큰. 다른 생성이 진행 중이면 empty. */
    Optional<String> tryAcquire(UUID userId);

    /** 토큰이 같을 때만 지운다. 실패는 WARN만 남긴다. */
    void release(UUID userId, String token);

    class LockUnavailableException extends RuntimeException {
        public LockUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

```java
package com.ymc.paper.service;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 선행지식 설명 결과별 누적 건수. */
@Component
public class PrerequisiteDefinitionMetrics {

    private final Counter hit;
    private final Counter generated;
    private final Counter failed;
    private final Counter rejectedConcurrent;
    private final Counter cacheError;

    public PrerequisiteDefinitionMetrics(MeterRegistry registry) {
        this.hit = outcome(registry, "hit");
        this.generated = outcome(registry, "generated");
        this.failed = outcome(registry, "failed");
        this.rejectedConcurrent = outcome(registry, "rejected_concurrent");
        this.cacheError = Counter.builder("prerequisite.definition.cache.errors").register(registry);
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("prerequisite.definitions").tag("outcome", outcome).register(registry);
    }

    public void hit() { hit.increment(); }
    public void generated() { generated.increment(); }
    public void failed() { failed.increment(); }
    public void rejectedConcurrent() { rejectedConcurrent.increment(); }
    public void cacheError() { cacheError.increment(); }
}
```

```java
package com.ymc.paper.infra.ai;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 선행지식 설명 생성 설정. generatorVersion은 기본값이 없어 누락되면 기동에 실패한다. */
@ConfigurationProperties(prefix = "prerequisite.definition")
public record PrerequisiteDefinitionProperties(Duration timeout, Duration cacheTtl, String generatorVersion) {

    public PrerequisiteDefinitionProperties {
        Objects.requireNonNull(timeout, "prerequisite.definition.timeout은 필수다.");
        Objects.requireNonNull(cacheTtl, "prerequisite.definition.cache-ttl은 필수다.");
        if (generatorVersion == null || generatorVersion.isBlank()) {
            throw new IllegalArgumentException("prerequisite.definition.generator-version은 필수다.");
        }
    }
}
```

`application.yml`의 `prerequisite:` 블록을 확장:

```yaml
prerequisite:
  backfill:
    enabled: false                  # true면 기동 시 컴파일 완료 Document의 선행지식을 한 번 채운다
  definition:
    timeout: 60s                    # AI non-streaming 응답 전체 상한. 잠금 만료는 여기에 5초를 더한다
    cache-ttl: ${PREREQUISITE_DEFINITION_CACHE_TTL:30d}
    generator-version: ${PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT}
```

`@ConfigurationProperties`는 `be/src/main/java/com/ymc/chat/infra/ChatStreamConfig.java:24`의 `@EnableConfigurationProperties({AiProperties.class, ChatStreamProperties.class})`로 등록된다. `paper` 컨텍스트에 같은 모양의 설정 클래스를 만든다:

```java
package com.ymc.paper.infra.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PrerequisiteDefinitionProperties.class)
public class PrerequisiteDefinitionConfig {
}
```

테스트 프로파일에서 `PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT`가 없으면 기동이 실패하므로 `IntegrationTest`의 `@SpringBootTest(properties = ...)`에 `"prerequisite.definition.generator-version=test-v1"`을 추가한다.

- [ ] **Step 7: 가짜 JSON AI 서버 작성**

```java
package com.ymc.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/** non-streaming AI 응답을 스크립트로 돌려주는 가짜 서버. 요청 본문·경로·호출 수를 기록한다. */
public final class FakeAiJsonServer implements AutoCloseable {

    public record Reply(int status, String json, long delayMillis) {
        public static Reply ok(String json) {
            return new Reply(200, json, 0);
        }

        public static Reply error(int status, String json) {
            return new Reply(status, json, 0);
        }

        public Reply delayed(long millis) {
            return new Reply(status, json, millis);
        }
    }

    private HttpServer server;
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("fake AI 서버 기동 실패", e);
        }
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastRequestPath.set(exchange.getRequestURI().getPath());
            Reply reply = replies.poll();
            if (reply == null) {
                reply = Reply.error(500, "{\"detail\":{\"code\":\"NO_SCRIPT\"}}");
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = reply.json().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public void enqueue(Reply reply) {
        replies.add(reply);
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String lastRequestBody() {
        return lastRequestBody.get();
    }

    public String lastRequestPath() {
        return lastRequestPath.get();
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        replies.clear();
        calls.set(0);
        lastRequestBody.set(null);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 고정 형식 definition 응답 JSON. */
    public static String definitionJson(String term, String en, String ko, String cost) {
        String md = "### " + term + "\\n\\n**Definition (정의)**\\n\\n" + en + "  \\n" + ko;
        return "{\"thread_id\":\"t\",\"paper_id\":\"p\",\"definition\":\"" + md
                + "\",\"estimated_cost_usd\":" + (cost == null ? "null" : "\"" + cost + "\"") + "}";
    }
}
```

- [ ] **Step 8: 어댑터 단위 테스트**

```java
package com.ymc.paper.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.port.PrerequisiteDefinitionGenerator.GenerationFailedException;
import com.ymc.support.FakeAiJsonServer;
import com.ymc.support.FakeAiJsonServer.Reply;

class AiPrerequisiteDefinitionAdapterTest {

    static FakeAiJsonServer aiServer;

    @BeforeAll
    static void start() {
        aiServer = new FakeAiJsonServer();
        aiServer.start();
    }

    @AfterAll
    static void stop() {
        aiServer.close();
    }

    @BeforeEach
    void reset() {
        aiServer.reset();
    }

    private AiPrerequisiteDefinitionAdapter adapter(Duration timeout) {
        return new AiPrerequisiteDefinitionAdapter(
                WebClient.builder().baseUrl(aiServer.baseUrl()).build(),
                new PrerequisiteDefinitionProperties(timeout, Duration.ofDays(30), "v1"),
                new ObjectMapper());
    }

    @Test
    void 계약_형식의_요청을_보내고_영문_국문을_돌려준다() throws Exception {
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("softmax", "An en line", "국문 한 줄", "0.00001")));

        PrerequisiteDefinition def = adapter(Duration.ofSeconds(5)).generate("paper-1", "p0000-b0002", 18, 25);

        assertThat(def).isEqualTo(new PrerequisiteDefinition("An en line", "국문 한 줄"));
        assertThat(aiServer.lastRequestPath()).isEqualTo("/api/v1/agents/prerequisite-knowledge-agent/runs");
        var body = new ObjectMapper().readTree(aiServer.lastRequestBody());
        assertThat(body.get("paper_id").asText()).isEqualTo("paper-1");
        assertThat(body.get("thread_id").asText()).isNotBlank();
        assertThat(body.at("/selection/start/block_id").asText()).isEqualTo("p0000-b0002");
        assertThat(body.at("/selection/start/offset").asInt()).isEqualTo(18);
        assertThat(body.at("/selection/end/block_id").asText()).isEqualTo("p0000-b0002");
        assertThat(body.at("/selection/end/offset").asInt()).isEqualTo(25);
    }

    @Test
    void AI_4xx는_GenerationFailedException이다() {
        aiServer.enqueue(Reply.error(400, "{\"detail\":{\"code\":\"PREREQUISITE_SELECTION_NOT_FOUND\","
                + "\"message\":\"no\",\"estimated_cost_usd\":\"0.00000000\"}}"));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(5)).generate("paper-1", "b", 0, 1))
                .isInstanceOf(GenerationFailedException.class)
                .hasMessageContaining("PREREQUISITE_SELECTION_NOT_FOUND");
    }

    @Test
    void 형식이_어긋난_definition은_GenerationFailedException이다() {
        aiServer.enqueue(Reply.ok("{\"thread_id\":\"t\",\"paper_id\":\"p\",\"definition\":\"free text\","
                + "\"estimated_cost_usd\":\"0.1\"}"));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(5)).generate("paper-1", "b", 0, 1))
                .isInstanceOf(GenerationFailedException.class)
                .hasMessageContaining("형식");
    }

    @Test
    void 타임아웃은_GenerationFailedException이다() {
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("t", "en", "ko", null)).delayed(1500));

        assertThatThrownBy(() -> adapter(Duration.ofMillis(300)).generate("paper-1", "b", 0, 1))
                .isInstanceOf(GenerationFailedException.class);
    }
}
```

- [ ] **Step 9: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.infra.ai.AiPrerequisiteDefinitionAdapterTest'`
Expected: 컴파일 실패.

- [ ] **Step 10: 어댑터 구현**

```java
package com.ymc.paper.infra.ai;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.PrerequisiteDefinitionMarkdown;
import com.ymc.paper.service.port.PrerequisiteDefinitionGenerator;

import lombok.RequiredArgsConstructor;

/**
 * BE↔AI 계약 prerequisite-knowledge-agent-run(non-streaming)의 WebClient 구현. 블록 호출이라
 * 타임아웃이 곧 전체 상한이다. 비용은 사용량에 반영하지 않고 로그로만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class AiPrerequisiteDefinitionAdapter implements PrerequisiteDefinitionGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiPrerequisiteDefinitionAdapter.class);
    static final String RUN_PATH = "/api/v1/agents/prerequisite-knowledge-agent/runs";

    private final WebClient aiWebClient;
    private final PrerequisiteDefinitionProperties properties;
    private final ObjectMapper objectMapper;

    record AnchorBody(@JsonProperty("block_id") String blockId, int offset) {
    }

    record SelectionBody(AnchorBody start, AnchorBody end) {
    }

    record RunRequestBody(
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("paper_id") String paperId,
            SelectionBody selection) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunResponseBody(String definition, @JsonProperty("estimated_cost_usd") BigDecimal estimatedCostUsd) {
    }

    @Override
    public PrerequisiteDefinition generate(String requestPaperId, String blockId, int startOffset, int endOffset) {
        RunRequestBody body = new RunRequestBody(UUID.randomUUID().toString(), requestPaperId,
                new SelectionBody(new AnchorBody(blockId, startOffset), new AnchorBody(blockId, endOffset)));
        RunResponseBody response;
        try {
            response = aiWebClient.post()
                    .uri(RUN_PATH)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(RunResponseBody.class)
                    .block(properties.timeout());
        } catch (WebClientResponseException e) {
            String code = errorCode(e.getResponseBodyAsString());
            throw new GenerationFailedException("AI 설명 생성 실패: status=" + e.getStatusCode().value()
                    + ", code=" + code, e);
        } catch (RuntimeException e) {
            // WebClient.block은 타임아웃을 IllegalStateException(cause TimeoutException)으로 올린다.
            boolean timeout = e.getCause() instanceof TimeoutException || e instanceof IllegalStateException;
            throw new GenerationFailedException(timeout ? "AI 설명 생성 타임아웃" : "AI 설명 생성 전송 실패", e);
        }
        if (response == null) {
            throw new GenerationFailedException("AI 설명 응답이 비어 있음", null);
        }
        log.info("선행지식 설명 생성: paperId={}, blockId={}, estimatedCostUsd={}",
                requestPaperId, blockId, response.estimatedCostUsd());
        return PrerequisiteDefinitionMarkdown.parse(response.definition())
                .orElseThrow(() -> new GenerationFailedException("AI 설명 형식 불일치", null));
    }

    private String errorCode(String responseBody) {
        try {
            JsonNode detail = objectMapper.readTree(responseBody).path("detail");
            return detail.path("code").asText("UNKNOWN");
        } catch (Exception e) {
            return "UNPARSEABLE";
        }
    }
}
```

`WebClient` 빈 이름이 `aiWebClient`인지 `AiWebClientConfig`에서 확인한다. 이름이 다르면 `@Qualifier`를 쓴다. 타임아웃 예외 판정은 테스트가 실제 어떤 예외를 던지는지 보고 조건을 맞춘다 — 테스트가 알려준다.

- [ ] **Step 11: 통과 확인**

Run: `./gradlew test --tests 'com.ymc.paper.infra.ai.AiPrerequisiteDefinitionAdapterTest'`
Expected: 4 PASS.

- [ ] **Step 12: 설명 API 통합 테스트**

캐시·잠금은 `@MockitoBean`으로 대체한다(Task 5가 실물로 바꾼다). AI는 `FakeAiJsonServer`를 띄우고 `ai.base-url`을 덮는다.

```java
package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.DocumentPrerequisiteHighlightIngestService;
import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;
import com.ymc.paper.service.port.PrerequisiteGenerationLock;
import com.ymc.support.FakeAiJsonServer;
import com.ymc.support.FakeAiJsonServer.Reply;
import com.ymc.support.IntegrationTest;

class PrerequisiteDefinitionIntegrationTest extends IntegrationTest {

    static FakeAiJsonServer aiServer = new FakeAiJsonServer();

    @BeforeAll
    static void startAi() {
        aiServer.start();
    }

    @AfterAll
    static void stopAi() {
        aiServer.close();
    }

    @DynamicPropertySource
    static void aiBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("ai.base-url", () -> aiServer.baseUrl());
    }

    @Autowired
    DocumentPrerequisiteHighlightIngestService highlightIngestService;

    @MockitoBean
    PrerequisiteDefinitionCache cache;

    @MockitoBean
    PrerequisiteGenerationLock lock;

    @BeforeEach
    void resetFakes() {
        aiServer.reset();
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(lock.tryAcquire(any())).thenReturn(Optional.of("token"));
    }

    private Paper givenCompiledPaper() {
        Paper paper = givenProcessingPaper("def.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = givenTranslatedPackageOnS3(paper.getId());
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);
        highlightIngestService.ingest(paper.getDocumentId(), manifestKey);
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null, null);
        return reload(paper.getId());
    }

    private static String url(Paper paper, String highlightId) {
        return "/api/papers/" + paper.getId() + "/prerequisite-highlights/" + highlightId + "/definition";
    }

    @Test
    void MISS면_AI를_불러_설명을_만들고_캐시에_넣는다() throws Exception {
        Paper paper = givenCompiledPaper();
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("new architecture", "An en line", "국문 한 줄", "0.1")));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.term").value("new architecture"))
                .andExpect(jsonPath("$.definitionEn").value("An en line"))
                .andExpect(jsonPath("$.definitionKo").value("국문 한 줄"));

        assertThat(aiServer.calls()).isEqualTo(1);
        assertThat(aiServer.lastRequestBody()).contains("\"paper_id\":\"" + paper.getId() + "\"")
                .contains("\"block_id\":\"p0000-b0001\"").contains("\"offset\":13").contains("\"offset\":29");
        verify(cache).put(anyString(), any(PrerequisiteDefinition.class));
        verify(lock).release(any(), any());
        assertThat(usageRecordRepository.findAll()).isEmpty();
    }

    @Test
    void HIT면_AI를_부르지_않고_잠금도_잡지_않는다() throws Exception {
        Paper paper = givenCompiledPaper();
        when(cache.get(anyString())).thenReturn(Optional.of(new PrerequisiteDefinition("cached en", "캐시 국문")));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionEn").value("cached en"));

        assertThat(aiServer.calls()).isZero();
        verify(lock, never()).tryAcquire(any());
    }

    @Test
    void 잠금을_못_잡으면_429다() throws Exception {
        Paper paper = givenCompiledPaper();
        when(lock.tryAcquire(any())).thenReturn(Optional.empty());

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED"));
        assertThat(aiServer.calls()).isZero();
    }

    @Test
    void AI_실패는_502이고_캐시에_넣지_않는다() throws Exception {
        Paper paper = givenCompiledPaper();
        aiServer.enqueue(Reply.error(502, "{\"detail\":{\"code\":\"DEFINITION_OUTPUT_INVALID\",\"message\":\"x\","
                + "\"estimated_cost_usd\":\"0.1\"}}"));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_DEFINITION_FAILED"));

        verify(cache, never()).put(anyString(), any());
        verify(lock).release(any(), any());
    }

    @Test
    void Valkey_장애로_잠금을_못_얻으면_AI를_부르지_않고_502다() throws Exception {
        Paper paper = givenCompiledPaper();
        when(lock.tryAcquire(any())).thenThrow(
                new PrerequisiteGenerationLock.LockUnavailableException("down", null));

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_DEFINITION_FAILED"));
        assertThat(aiServer.calls()).isZero();
    }

    @Test
    void 없는_highlightId는_404다() throws Exception {
        Paper paper = givenCompiledPaper();

        mockMvc.perform(post(url(paper, "prerequisite-9999")).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_HIGHLIGHT_NOT_FOUND"));
    }

    @Test
    void 컴파일_전이면_409다() throws Exception {
        Paper paper = givenProcessingPaper("notready.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));

        mockMvc.perform(post(url(reload(paper.getId()), "prerequisite-0001")).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_NOT_READY"));
    }

    @Test
    void 남의_논문은_403이다() throws Exception {
        Paper paper = givenCompiledPaper();

        mockMvc.perform(post(url(paper, "prerequisite-0001")).with(otherUserJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_논문은_404다() throws Exception {
        mockMvc.perform(post("/api/papers/" + UUID.randomUUID() + "/prerequisite-highlights/h/definition")
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }
}
```

`@MockitoBean`은 Spring Boot 3.4+의 `org.springframework.test.context.bean.override.mockito.MockitoBean`이다. `IntegrationTest`에는 mock bean 사용이 없으므로 이 클래스에서 처음 쓴다. 필드에 붙이면 이 테스트 클래스의 컨텍스트만 따로 뜬다(캐시 미공유) — 허용한다.

- [ ] **Step 13: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PrerequisiteDefinitionIntegrationTest'`
Expected: 컴파일 실패 (포트 빈·서비스·컨트롤러 없음). 포트 빈이 없어 컨텍스트 기동 실패도 가능 — Task 5 전까지는 `@MockitoBean`이 빈을 만들어 준다.

- [ ] **Step 14: 서비스·DTO·컨트롤러 구현**

```java
package com.ymc.paper.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentContentRepository;
import com.ymc.paper.domain.DocumentPrerequisiteHighlight;
import com.ymc.paper.domain.DocumentPrerequisiteHighlightRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.infra.ai.PrerequisiteDefinitionProperties;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;
import com.ymc.paper.service.port.PrerequisiteDefinitionGenerator;
import com.ymc.paper.service.port.PrerequisiteGenerationLock;

import lombok.RequiredArgsConstructor;

/**
 * 선행지식 설명 조회 또는 생성. 검증은 짧은 트랜잭션에서 끝내고, 캐시·잠금·AI 호출은 트랜잭션 밖에서 한다.
 * 사용량은 차감하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PrerequisiteDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(PrerequisiteDefinitionService.class);

    private final PaperRepository paperRepository;
    private final PaperDocumentViews views;
    private final DocumentContentRepository contentRepository;
    private final DocumentPrerequisiteHighlightRepository highlightRepository;
    private final PrerequisiteDefinitionCache cache;
    private final PrerequisiteGenerationLock lock;
    private final PrerequisiteDefinitionGenerator generator;
    private final PrerequisiteDefinitionProperties properties;
    private final PrerequisiteDefinitionMetrics metrics;

    public record PrerequisiteDefinitionView(String term, String definitionEn, String definitionKo) {
    }

    /** 검증 결과. AI 요청과 캐시 키에 필요한 값만 담는다. */
    record Resolved(UUID documentId, UUID requestPaperId, DocumentPrerequisiteHighlight highlight) {
    }

    public PrerequisiteDefinitionView define(UUID paperId, UUID ownerId, String highlightId) {
        Resolved resolved = resolve(paperId, ownerId, highlightId);
        DocumentPrerequisiteHighlight h = resolved.highlight();
        String key = cacheKey(resolved.documentId(), h.getText());

        Optional<PrerequisiteDefinition> cached = cache.get(key);
        if (cached.isPresent()) {
            metrics.hit();
            return view(h, cached.get());
        }

        String token;
        try {
            token = lock.tryAcquire(ownerId).orElse(null);
        } catch (PrerequisiteGenerationLock.LockUnavailableException e) {
            metrics.failed();
            log.warn("선행지식 잠금 저장소 장애, 생성 거절: ownerId={}", ownerId, e);
            throw new ApiException(ErrorCode.PREREQUISITE_DEFINITION_FAILED, "설명을 생성할 수 없습니다.");
        }
        if (token == null) {
            metrics.rejectedConcurrent();
            throw new ApiException(ErrorCode.PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED,
                    "다른 선행지식 설명을 생성 중입니다.");
        }
        try {
            PrerequisiteDefinition generated = generator.generate(
                    resolved.requestPaperId().toString(), h.getBlockId(), h.getStartOffset(), h.getEndOffset());
            cache.put(key, generated);
            metrics.generated();
            return view(h, generated);
        } catch (PrerequisiteDefinitionGenerator.GenerationFailedException e) {
            metrics.failed();
            log.warn("선행지식 설명 생성 실패: documentId={}, highlightId={}", resolved.documentId(), highlightId, e);
            throw new ApiException(ErrorCode.PREREQUISITE_DEFINITION_FAILED, "설명 생성에 실패했습니다.");
        } finally {
            lock.release(ownerId, token);
        }
    }

    /**
     * @throws ApiException PAPER_NOT_FOUND(404), FORBIDDEN(403), PREREQUISITE_NOT_READY(409),
     *         PREREQUISITE_HIGHLIGHT_NOT_FOUND(404)
     */
    @Transactional(readOnly = true)
    protected Resolved resolve(UUID paperId, UUID ownerId, String highlightId) {
        Paper paper = paperRepository.findActiveById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다."));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        Document document = views.documentOf(paper).orElseThrow(
                () -> new ApiException(ErrorCode.PREREQUISITE_NOT_READY, "논문이 아직 준비되지 않았습니다."));
        if (document.getStatus() != DocumentStatus.COMPLETED
                || document.getCompileStatus() != CompileStatus.COMPLETED
                || contentRepository.findById(document.getId()).isEmpty()) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_READY, "선행지식이 아직 준비되지 않았습니다.");
        }
        DocumentPrerequisiteHighlight highlight = highlightRepository
                .findByDocumentIdAndHighlightId(document.getId(), highlightId).orElseThrow(
                        () -> new ApiException(ErrorCode.PREREQUISITE_HIGHLIGHT_NOT_FOUND, "없는 선행지식입니다."));
        return new Resolved(document.getId(), document.getRequestPaperId(), highlight);
    }

    private PrerequisiteDefinitionView view(DocumentPrerequisiteHighlight h, PrerequisiteDefinition d) {
        return new PrerequisiteDefinitionView(h.getText(), d.definitionEn(), d.definitionKo());
    }

    String cacheKey(UUID documentId, String term) {
        return "prerequisite-definition:v1:" + documentId + ":" + properties.generatorVersion()
                + ":" + sha256Hex(normalize(term));
    }

    static String normalize(String term) {
        return Normalizer.normalize(term, Normalizer.Form.NFC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

주의: `@Transactional`이 붙은 `resolve`를 같은 클래스에서 `this.resolve(...)`로 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다. 검증 4 SELECT는 각각 auto-commit으로 돌아도 결과가 같으므로 MVP에서는 이대로 두되, 주석에 남긴다: `// 자기 호출이라 readOnly 트랜잭션은 걸리지 않는다. 조회 4개가 각각 auto-commit이어도 결과는 같다.` `@Transactional` 애노테이션은 제거한다(오해 방지). `views.documentOf(paper)`의 존재는 `PaperContentQueryService`에서 확인했다(`PaperDocumentViews`).

`ApiException`의 패키지·생성자 시그니처는 `PaperContentQueryService`의 import를 따른다.

정규화 단위 테스트를 `PrerequisiteDefinitionMarkdownTest` 옆에 추가:

```java
package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrerequisiteDefinitionServiceNormalizeTest {

    @Test
    void NFC_공백_소문자_정규화() {
        String decomposed = "Café  Term ";  // e + combining acute
        assertThat(PrerequisiteDefinitionService.normalize(decomposed)).isEqualTo("café term");
    }

    @Test
    void 호환_문자는_합치지_않는다() {
        assertThat(PrerequisiteDefinitionService.normalize("x²")).isEqualTo("x²");
    }
}
```

DTO:

```java
package com.ymc.paper.api.dto;

import com.ymc.paper.service.PrerequisiteDefinitionService.PrerequisiteDefinitionView;

/** 계약 `PrerequisiteDefinitionResponse`. */
public record PrerequisiteDefinitionResponse(String term, String definitionEn, String definitionKo) {

    public static PrerequisiteDefinitionResponse from(PrerequisiteDefinitionView view) {
        return new PrerequisiteDefinitionResponse(view.term(), view.definitionEn(), view.definitionKo());
    }
}
```

컨트롤러에 필드 `private final PrerequisiteDefinitionService prerequisiteDefinitionService;`와 operation 추가:

```java
    /** 선행지식 설명 조회 또는 생성. body 없음, 사용량 미차감. */
    @PostMapping("/{paperId}/prerequisite-highlights/{highlightId}/definition")
    public PrerequisiteDefinitionResponse prerequisiteDefinition(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paperId, @PathVariable String highlightId) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return PrerequisiteDefinitionResponse.from(
                prerequisiteDefinitionService.define(paperId, ownerId, highlightId));
    }
```

- [ ] **Step 15: 통과 확인과 전체 회귀**

Run: `./gradlew test --tests 'com.ymc.paper.api.PrerequisiteDefinitionIntegrationTest' --tests 'com.ymc.paper.service.PrerequisiteDefinitionServiceNormalizeTest'`
Expected: 모두 PASS.
Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 16: 커밋**

```bash
git add be/src/main/java/com/ymc/common/error/ErrorCode.java \
  be/src/main/java/com/ymc/paper/service/port/PrerequisiteDefinitionGenerator.java \
  be/src/main/java/com/ymc/paper/service/port/PrerequisiteDefinitionCache.java \
  be/src/main/java/com/ymc/paper/service/port/PrerequisiteGenerationLock.java \
  be/src/main/java/com/ymc/paper/service/PrerequisiteDefinition.java \
  be/src/main/java/com/ymc/paper/service/PrerequisiteDefinitionMarkdown.java \
  be/src/main/java/com/ymc/paper/service/PrerequisiteDefinitionService.java \
  be/src/main/java/com/ymc/paper/service/PrerequisiteDefinitionMetrics.java \
  be/src/main/java/com/ymc/paper/infra/ai/AiPrerequisiteDefinitionAdapter.java \
  be/src/main/java/com/ymc/paper/infra/ai/PrerequisiteDefinitionProperties.java \
  be/src/main/java/com/ymc/paper/infra/ai/PrerequisiteDefinitionConfig.java \
  be/src/main/java/com/ymc/paper/api/dto/PrerequisiteDefinitionResponse.java \
  be/src/main/java/com/ymc/paper/api/PaperController.java \
  be/src/main/resources/application.yml \
  be/src/test/java/com/ymc/paper/service/PrerequisiteDefinitionMarkdownTest.java \
  be/src/test/java/com/ymc/paper/service/PrerequisiteDefinitionServiceNormalizeTest.java \
  be/src/test/java/com/ymc/support/FakeAiJsonServer.java \
  be/src/test/java/com/ymc/support/IntegrationTest.java \
  be/src/test/java/com/ymc/paper/infra/ai/AiPrerequisiteDefinitionAdapterTest.java \
  be/src/test/java/com/ymc/paper/api/PrerequisiteDefinitionIntegrationTest.java
git commit -m "[YMC-414] feat(be): 선행지식 설명 API와 AI non-streaming 호출"
```


---

### Task 5: Valkey 캐시와 사용자별 잠금 (BE)

**Files:**
- Modify: `be/build.gradle` (data-redis 의존성)
- Create: `be/src/main/java/com/ymc/paper/infra/cache/ValkeyPrerequisiteDefinitionCache.java`
- Create: `be/src/main/java/com/ymc/paper/infra/cache/ValkeyPrerequisiteGenerationLock.java`
- Modify: `be/src/main/resources/application.yml` (`management.health.redis.enabled: false`)
- Create: `be/src/test/java/com/ymc/support/ValkeyTestConfiguration.java`
- Modify: `be/src/test/java/com/ymc/support/IntegrationTest.java` (`@Import`에 추가)
- Test: `be/src/test/java/com/ymc/paper/infra/cache/ValkeyPrerequisiteDefinitionCacheIntegrationTest.java`
- Test: `be/src/test/java/com/ymc/paper/infra/cache/ValkeyPrerequisiteGenerationLockIntegrationTest.java`
- Modify: `be/src/test/java/com/ymc/paper/api/PrerequisiteDefinitionIntegrationTest.java` (`@MockitoBean` 제거, 실물로 2케이스 추가)

**Interfaces:**
- Consumes: `PrerequisiteDefinitionCache`, `PrerequisiteGenerationLock`, `PrerequisiteDefinitionProperties`, `PrerequisiteDefinitionMetrics`.

- [ ] **Step 1: 의존성과 테스트 컨테이너**

`build.gradle`의 `spring-boot-starter-data-jpa` 아래에:

```groovy
	implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

`ValkeyTestConfiguration`:

```java
package com.ymc.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** 통합 테스트용 Valkey. 컨테이너를 빈으로 등록해 컨텍스트와 함께 재사용한다. */
@TestConfiguration(proxyBeanMethods = false)
public class ValkeyTestConfiguration {

    /** ⚠ 이미지 태그는 infra/local의 valkey 서비스와 일치해야 한다. */
    private static final String VALKEY_IMAGE = "valkey/valkey:7.2.14-alpine";

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> valkeyContainer() {
        return new GenericContainer<>(VALKEY_IMAGE).withExposedPorts(6379);
    }
}
```

`IntegrationTest`의 `@Import({TestcontainersConfiguration.class, LocalStackTestConfiguration.class})`에 `ValkeyTestConfiguration.class`를 추가하고 `protected StringRedisTemplate redisTemplate;`를 `@Autowired`로 둔다. `setUp`의 `deleteAll` 목록 앞에 `redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();`를 넣는다.

`application.yml` 최상위 `management:` 블록(6행)에:

```yaml
management:
  health:
    redis:
      enabled: false                # Valkey 장애가 BE health check를 떨어뜨리지 않게 한다
```

- [ ] **Step 2: 캐시 통합 테스트**

```java
package com.ymc.paper.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;
import com.ymc.support.IntegrationTest;

class ValkeyPrerequisiteDefinitionCacheIntegrationTest extends IntegrationTest {

    @Autowired
    PrerequisiteDefinitionCache cache;

    @Test
    void 넣은_값을_그대로_돌려주고_TTL이_설정된다() {
        cache.put("prerequisite-definition:v1:d:v:h", new PrerequisiteDefinition("en", "ko"));

        assertThat(cache.get("prerequisite-definition:v1:d:v:h"))
                .contains(new PrerequisiteDefinition("en", "ko"));
        Long ttl = redisTemplate.getExpire("prerequisite-definition:v1:d:v:h");
        assertThat(ttl).isGreaterThan(Duration.ofDays(29).toSeconds());
        assertThat(redisTemplate.opsForValue().get("prerequisite-definition:v1:d:v:h"))
                .contains("\"definitionEn\"").contains("\"generatedAt\"");
    }

    @Test
    void 먼저_저장된_값이_남는다() {
        cache.put("k", new PrerequisiteDefinition("first", "첫째"));
        cache.put("k", new PrerequisiteDefinition("second", "둘째"));

        assertThat(cache.get("k")).contains(new PrerequisiteDefinition("first", "첫째"));
    }

    @Test
    void 없는_키는_empty다() {
        assertThat(cache.get("nope")).isEmpty();
    }

    @Test
    void 깨진_값은_empty다() {
        redisTemplate.opsForValue().set("broken", "not json");
        assertThat(cache.get("broken")).isEmpty();
    }
}
```

- [ ] **Step 3: 잠금 통합 테스트**

```java
package com.ymc.paper.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.service.port.PrerequisiteGenerationLock;
import com.ymc.support.IntegrationTest;

class ValkeyPrerequisiteGenerationLockIntegrationTest extends IntegrationTest {

    @Autowired
    PrerequisiteGenerationLock lock;

    @Test
    void 같은_사용자는_동시에_하나만_잡는다() {
        UUID user = UUID.randomUUID();
        Optional<String> first = lock.tryAcquire(user);
        Optional<String> second = lock.tryAcquire(user);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(lock.tryAcquire(UUID.randomUUID())).isPresent();
    }

    @Test
    void 해제하면_다시_잡을_수_있다() {
        UUID user = UUID.randomUUID();
        String token = lock.tryAcquire(user).orElseThrow();

        lock.release(user, token);

        assertThat(lock.tryAcquire(user)).isPresent();
    }

    @Test
    void 다른_토큰으로는_풀리지_않는다() {
        UUID user = UUID.randomUUID();
        lock.tryAcquire(user).orElseThrow();

        lock.release(user, "stale-token");

        assertThat(lock.tryAcquire(user)).isEmpty();
    }

    @Test
    void 만료는_타임아웃_더하기_5초다() {
        UUID user = UUID.randomUUID();
        lock.tryAcquire(user).orElseThrow();

        Long ttl = redisTemplate.getExpire("prerequisite-definition:lock:" + user);
        assertThat(ttl).isBetween(60L, 65L);
    }
}
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.infra.cache.*'`
Expected: 컨텍스트 기동 실패 — `PrerequisiteDefinitionCache` 빈 없음.

- [ ] **Step 5: 캐시·잠금 구현**

```java
package com.ymc.paper.infra.cache;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.infra.ai.PrerequisiteDefinitionProperties;
import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.PrerequisiteDefinitionMetrics;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;

import lombok.RequiredArgsConstructor;

/** 설명을 SET NX + 고정 TTL로 저장한다. 먼저 저장된 값이 남고 조회는 TTL을 늘리지 않는다. */
@Component
@RequiredArgsConstructor
public class ValkeyPrerequisiteDefinitionCache implements PrerequisiteDefinitionCache {

    private static final Logger log = LoggerFactory.getLogger(ValkeyPrerequisiteDefinitionCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PrerequisiteDefinitionProperties properties;
    private final PrerequisiteDefinitionMetrics metrics;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Value(String definitionEn, String definitionKo, Instant generatedAt) {
    }

    @Override
    public Optional<PrerequisiteDefinition> get(String key) {
        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            metrics.cacheError();
            log.warn("선행지식 캐시 조회 실패, MISS로 진행: key={}", key, e);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            Value v = objectMapper.readValue(json, Value.class);
            if (v.definitionEn() == null || v.definitionKo() == null) {
                return Optional.empty();
            }
            return Optional.of(new PrerequisiteDefinition(v.definitionEn(), v.definitionKo()));
        } catch (JacksonException e) {
            log.warn("선행지식 캐시 값 역직렬화 실패, MISS로 진행: key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, PrerequisiteDefinition value) {
        try {
            String json = objectMapper.writeValueAsString(
                    new Value(value.definitionEn(), value.definitionKo(), Instant.now()));
            redis.opsForValue().setIfAbsent(key, json, properties.cacheTtl());
        } catch (RuntimeException | JacksonException e) {
            metrics.cacheError();
            log.warn("선행지식 캐시 저장 실패, 생성 결과는 그대로 반환: key={}", key, e);
        }
    }
}
```

`JacksonException`이 `RuntimeException`의 하위면 multi-catch가 컴파일 오류가 난다 — 그 경우 `catch (Exception e)`로 바꾼다.

```java
package com.ymc.paper.infra.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ymc.paper.infra.ai.PrerequisiteDefinitionProperties;
import com.ymc.paper.service.port.PrerequisiteGenerationLock;

/** 사용자당 잠금 1개를 SET NX PX로 잡고, 토큰이 같을 때만 Lua로 지운다. */
@Component
public class ValkeyPrerequisiteGenerationLock implements PrerequisiteGenerationLock {

    private static final Logger log = LoggerFactory.getLogger(ValkeyPrerequisiteGenerationLock.class);
    private static final Duration GRACE = Duration.ofSeconds(5);
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration expiry;

    public ValkeyPrerequisiteGenerationLock(StringRedisTemplate redis, PrerequisiteDefinitionProperties properties) {
        this.redis = redis;
        this.expiry = properties.timeout().plus(GRACE);
    }

    static String key(UUID userId) {
        return "prerequisite-definition:lock:" + userId;
    }

    @Override
    public Optional<String> tryAcquire(UUID userId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key(userId), token, expiry);
        } catch (RuntimeException e) {
            throw new LockUnavailableException("선행지식 잠금 저장소에 접근할 수 없습니다.", e);
        }
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    @Override
    public void release(UUID userId, String token) {
        try {
            redis.execute(RELEASE_IF_OWNER, List.of(key(userId)), token);
        } catch (RuntimeException e) {
            log.warn("선행지식 잠금 해제 실패, 만료로 풀린다: userId={}", userId, e);
        }
    }
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew test --tests 'com.ymc.paper.infra.cache.*'`
Expected: 8 PASS.

- [ ] **Step 7: 설명 API 테스트를 실물 캐시·잠금으로 전환**

`PrerequisiteDefinitionIntegrationTest`에서 `@MockitoBean` 두 필드와 `when(cache...)`, `when(lock...)`, `verify(...)` 줄을 모두 제거한다. 대신 실물로 검증하는 케이스로 바꾼다.

- `MISS면_AI를_불러_설명을_만들고_캐시에_넣는다`: `verify(cache).put` → `assertThat(redisTemplate.keys("prerequisite-definition:v1:*")).hasSize(1)`, `verify(lock).release` → `assertThat(redisTemplate.hasKey("prerequisite-definition:lock:" + TEST_USER_ID)).isFalse()`.
- `HIT면_AI를_부르지_않고_잠금도_잡지_않는다`: 첫 요청으로 캐시를 채운 뒤 `aiServer.reset()`, 두 번째 요청 뒤 `aiServer.calls()`가 0.
- `잠금을_못_잡으면_429다`: `redisTemplate.opsForValue().set("prerequisite-definition:lock:" + TEST_USER_ID, "busy", Duration.ofSeconds(30))`로 선점한 뒤 요청.
- `AI_실패는_502이고_캐시에_넣지_않는다`: `redisTemplate.keys("prerequisite-definition:v1:*")`가 비어 있고 lock 키가 없는지.
- `Valkey_장애로_잠금을_못_얻으면_AI를_부르지_않고_502다`: `@MockitoBean` 없이는 Valkey를 죽이기 어려우므로 이 케이스만 `PrerequisiteGenerationLock`을 `@MockitoBean`으로 두는 **별도 클래스** `PrerequisiteDefinitionLockOutageIntegrationTest`로 옮긴다. 다른 케이스는 실물이다.

추가 케이스:

```java
    @Test
    void 같은_Document의_같은_표기는_다른_사용자도_캐시를_공유한다() throws Exception {
        Paper mine = givenCompiledPaper();
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("new architecture", "en", "ko", "0.1")));
        mockMvc.perform(post(url(mine, "prerequisite-0001")).with(userJwt())).andExpect(status().isOk());

        // 같은 PDF를 다른 사용자가 올리면 같은 Document에 연결된다.
        Paper theirs = givenProcessingPaperOwnedBy(OTHER_USER_ID, "def-other.pdf");
        // ↑ 이 헬퍼가 없으면 IntegrationTest의 givenProcessingPaper 구현을 보고 ownerId 인자를 받는 오버로드를 추가한다.
        aiServer.reset();

        mockMvc.perform(post(url(theirs, "prerequisite-0001")).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionEn").value("en"));
        assertThat(aiServer.calls()).isZero();
    }
```

`givenProcessingPaper`는 `randomChecksum()`으로 Paper마다 다른 Document를 만든다(`IntegrationTest.java:239-262`). 다른 사용자를 같은 Document에 붙이는 헬퍼가 없으므로 이 케이스는 아래처럼 **같은 Document를 직접 공유**하는 형태로 쓴다: `givenCompiledPaper()`로 만든 Paper의 `documentId`를 읽고, `paperRepository.save(Paper.of(...OTHER_USER_ID...))`로 다른 사용자의 Paper를 만든 뒤 `PaperDocumentLinkService` 또는 `Paper` 엔티티의 연결 메서드로 같은 `documentId`에 연결한다. 연결 메서드 이름은 `be/src/main/java/com/ymc/paper/domain/Paper.java`에서 확인한다(`grep -n "documentId" Paper.java`). 그것도 번거로우면 케이스를 "같은 사용자가 같은 하이라이트를 두 번 요청하면 두 번째는 AI를 부르지 않는다"로 축소하고, 사용자 간 공유는 캐시 키에 사용자 id가 없다는 `cacheKey` 단위 테스트로 대신한다.

- [ ] **Step 8: 통과 확인과 전체 회귀**

Run: `./gradlew test --tests 'com.ymc.paper.api.PrerequisiteDefinition*'`
Expected: 모두 PASS.
Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: 커밋**

```bash
git add be/build.gradle be/src/main/resources/application.yml \
  be/src/main/java/com/ymc/paper/infra/cache/ \
  be/src/test/java/com/ymc/support/ValkeyTestConfiguration.java \
  be/src/test/java/com/ymc/support/IntegrationTest.java \
  be/src/test/java/com/ymc/paper/infra/cache/ \
  be/src/test/java/com/ymc/paper/api/PrerequisiteDefinitionIntegrationTest.java \
  be/src/test/java/com/ymc/paper/api/PrerequisiteDefinitionLockOutageIntegrationTest.java
git commit -m "[YMC-414] feat(be): 선행지식 설명 Valkey 30일 캐시와 사용자별 동시 생성 잠금"
```

---

### Task 6: FE 타입·API·하이라이트 렌더

**Files:**
- Modify: `fe/src/api/types.ts`
- Modify: `fe/src/api/papers.ts`
- Modify: `fe/src/markdown/paperContent.ts`
- Create: `fe/src/markdown/rehypePrerequisiteHighlight.ts`
- Modify: `fe/src/markdown/PaperMarkdown.tsx`
- Modify: `fe/src/markdown/markdown.css`
- Modify: `fe/src/routes/study/PaperViewer.tsx`
- Test: `fe/src/markdown/rehypePrerequisiteHighlight.test.tsx`
- Test: `fe/src/markdown/paperContent.test.ts` (케이스 추가)
- Test: `fe/src/routes/study/sourceOffset.test.ts` (케이스 추가)

**Interfaces:**
- Produces:
  - `PrerequisiteHighlight { highlightId: string; blockId: string; startOffset: number; endOffset: number; text: string }` in `types.ts`; `PaperContentResponse.prerequisiteHighlights: PrerequisiteHighlight[]`; `PrerequisiteDefinitionResponse { term; definitionEn; definitionKo }`.
  - `createPrerequisiteDefinition(paperId: string, highlightId: string, signal?: AbortSignal): Promise<PrerequisiteDefinitionResponse>`.
  - `PaperBlock.highlights?: HighlightRange[]` where `HighlightRange { id: string; start: number; end: number }` (원문 offset). `PaperContent.prerequisiteHighlights: PrerequisiteHighlight[]`.
  - `rehypePrerequisiteHighlight(options: { ranges: HighlightRange[]; shift: number })` — hast 플러그인. `<mark class="term-highlight" data-highlight-id role="button" tabindex="0">`.
  - `PaperMarkdown` prop `highlights?: HighlightRange[]` (markdown 좌표로 변환된 값), `PaperViewer` prop `prerequisiteVisible: boolean`.

- [ ] **Step 1: 타입과 API**

`types.ts`의 `PaperContentResponse` 앞에:

```ts
// 선행지식 하이라이트. offset은 UTF-16 code unit, start 포함·end 제외. text는 해당 범위 원문.
export interface PrerequisiteHighlight {
  highlightId: string;
  blockId: string;
  startOffset: number;
  endOffset: number;
  text: string;
}

// POST /api/papers/{paperId}/prerequisite-highlights/{highlightId}/definition
export interface PrerequisiteDefinitionResponse {
  term: string;
  definitionEn: string;
  definitionKo: string;
}
```

`PaperContentResponse`에 `prerequisiteHighlights: PrerequisiteHighlight[];` 추가.

`papers.ts`의 `fetchPaperContent` 아래:

```ts
// 선행지식 설명 조회 또는 생성. 캐시 HIT면 즉시, MISS면 AI 생성 뒤 응답한다.
export async function createPrerequisiteDefinition(
  paperId: string,
  highlightId: string,
  signal?: AbortSignal,
): Promise<PrerequisiteDefinitionResponse> {
  const res = await authFetch(
    `/api/papers/${paperId}/prerequisite-highlights/${encodeURIComponent(highlightId)}/definition`,
    { method: 'POST', signal },
  );
  if (!res.ok) throw await apiError(res);
  return res.json();
}
```

import에 `PrerequisiteDefinitionResponse` 추가. `authFetch`의 두 번째 인자가 `RequestInit`인지 `auth.ts:108`에서 확인한다.

Run: `npm run typecheck`
Expected: `prerequisiteHighlights` 누락으로 기존 테스트 fixture가 오류 → 다음 Step에서 처리.

- [ ] **Step 2: paperContent 테스트 추가**

`paperContent.test.ts`에 추가. 기존 fixture 만드는 헬퍼 이름을 파일에서 확인해 쓴다(아래는 `res`가 `PaperContentResponse`라고 가정).

```ts
describe('prerequisiteHighlights', () => {
  it('blockId별로 블록에 원문 offset 범위를 붙인다', () => {
    const res: PaperContentResponse = {
      paperId: 'p', title: null, sourceLanguage: 'en', translationStatus: 'READY', schemaVersion: 1,
      assets: {},
      blocks: [
        { blockId: 'b1', globalOrder: 0, label: 'paragraph_title', headingLevel: 2, sectionPath: [], content: { format: 'text', text: 'Intro' } },
        { blockId: 'b2', globalOrder: 1, label: 'text', headingLevel: null, sectionPath: [], content: { format: 'text', text: 'softmax and dropout' } },
      ],
      prerequisiteHighlights: [
        { highlightId: 'h2', blockId: 'b2', startOffset: 12, endOffset: 19, text: 'dropout' },
        { highlightId: 'h1', blockId: 'b2', startOffset: 0, endOffset: 7, text: 'softmax' },
        { highlightId: 'h0', blockId: 'b1', startOffset: 0, endOffset: 5, text: 'Intro' },
      ],
    };
    const content = adaptPaperContent(res);
    expect(content.blocks[0].highlights).toEqual([{ id: 'h0', start: 0, end: 5 }]);
    expect(content.blocks[1].highlights).toEqual([
      { id: 'h2', start: 12, end: 19 },
      { id: 'h1', start: 0, end: 7 },
    ]);
    expect(content.prerequisiteHighlights).toHaveLength(3);
  });

  it('atomic 블록이나 없는 blockId의 하이라이트는 버린다', () => {
    const res: PaperContentResponse = {
      paperId: 'p', title: null, sourceLanguage: 'en', translationStatus: 'READY', schemaVersion: 1,
      assets: {},
      blocks: [
        { blockId: 'eq', globalOrder: 0, label: 'display_formula', headingLevel: null, sectionPath: [], content: { format: 'formula', tex: 'x' } },
      ],
      prerequisiteHighlights: [
        { highlightId: 'h1', blockId: 'eq', startOffset: 0, endOffset: 1, text: 'x' },
        { highlightId: 'h2', blockId: 'nope', startOffset: 0, endOffset: 1, text: 'x' },
      ],
    };
    const content = adaptPaperContent(res);
    expect(content.blocks[0].highlights).toBeUndefined();
  });
});
```

기존 fixture 객체들에 `prerequisiteHighlights: []`를 추가한다.

- [ ] **Step 3: 실패 확인**

Run: `npm test -- --run src/markdown/paperContent.test.ts`
Expected: FAIL — `highlights` undefined.

- [ ] **Step 4: paperContent 구현**

`paperContent.ts`:

```ts
export interface HighlightRange { id: string; start: number; end: number; }
```

`PaperBlock`에 `/** 선행지식 범위(원문 offset). atomic 블록엔 없다. */ highlights?: HighlightRange[];`, `PaperContent`에 `prerequisiteHighlights: PrerequisiteHighlight[];`. import에 `PrerequisiteHighlight` 추가.

`adaptPaperContent`에서 `const blocks = res.blocks.map(...)` 다음:

```ts
  const byBlock = new Map<string, HighlightRange[]>();
  for (const h of res.prerequisiteHighlights ?? []) {
    const list = byBlock.get(h.blockId) ?? [];
    list.push({ id: h.highlightId, start: h.startOffset, end: h.endOffset });
    byBlock.set(h.blockId, list);
  }
  for (const b of blocks) {
    const ranges = byBlock.get(b.id);
    // sourceText가 없는 블록은 offset을 그릴 수 없다.
    if (ranges && b.sourceText !== undefined) b.highlights = ranges;
  }
```

반환 객체에 `prerequisiteHighlights: res.prerequisiteHighlights ?? [],` 추가.

Run: `npm test -- --run src/markdown/paperContent.test.ts`
Expected: PASS.

- [ ] **Step 5: rehype 플러그인 테스트**

`rehypeSourcePos.test.tsx`의 렌더 방식(어떤 헬퍼로 `PaperMarkdown`을 그리는지)을 보고 같은 방식으로 쓴다.

```tsx
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PaperMarkdown } from './PaperMarkdown';

function marks(container: HTMLElement) {
  return Array.from(container.querySelectorAll('mark.term-highlight')).map((m) => ({
    id: m.getAttribute('data-highlight-id'),
    text: m.textContent,
    srcStart: m.querySelector('[data-src-start]')?.getAttribute('data-src-start'),
  }));
}

describe('rehypePrerequisiteHighlight', () => {
  it('한 범위를 mark로 감싸고 조각마다 data-src-start가 붙는다', () => {
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h1', start: 8, end: 15 }]}>
        {'softmax dropout layer'}
      </PaperMarkdown>,
    );
    expect(marks(container)).toEqual([{ id: 'h1', text: 'dropout', srcStart: '8' }]);
    const spans = Array.from(container.querySelectorAll('[data-src-start]')).map((s) => s.getAttribute('data-src-start'));
    expect(spans).toEqual(['0', '8', '15']);
  });

  it('한 블록의 여러 범위를 모두 감싼다', () => {
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'a', start: 0, end: 7 }, { id: 'b', start: 16, end: 21 }]}>
        {'softmax dropout layer'}
      </PaperMarkdown>,
    );
    expect(marks(container).map((m) => m.text)).toEqual(['softmax', 'layer']);
  });

  it('heading은 shift만큼 밀린 markdown 좌표로 그린다', () => {
    // 원문 'Intro model'의 6..11 = 'model'. markdown '## Intro model'에서는 9..14.
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 9, end: 14 }]}>{'## Intro model'}</PaperMarkdown>,
    );
    expect(marks(container)).toEqual([{ id: 'h', text: 'model', srcStart: '9' }]);
  });

  it('인라인 수식 옆 텍스트도 감싼다', () => {
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 0, end: 7 }]}>{'softmax $x$ tail'}</PaperMarkdown>,
    );
    expect(marks(container).map((m) => m.text)).toEqual(['softmax']);
  });

  it('보조평면 문자 뒤 범위도 UTF-16 offset으로 맞는다', () => {
    // '𝑥 dropout': 𝑥는 code unit 2개라 dropout은 3..10.
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 3, end: 10 }]}>{'𝑥 dropout'}</PaperMarkdown>,
    );
    expect(marks(container)).toEqual([{ id: 'h', text: 'dropout', srcStart: '3' }]);
  });

  it('1:1 매핑이 안 되는 노드에 걸친 범위는 그리지 않는다', () => {
    // 강조 안쪽 텍스트는 markdown 소스와 내용이 같아 감싸지지만, 범위가 ** 경계를 넘으면 버린다.
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 0, end: 10 }]}>{'ab **cd** ef'}</PaperMarkdown>,
    );
    expect(marks(container)).toEqual([]);
  });

  it('highlights가 없으면 mark를 만들지 않는다', () => {
    const { container } = render(<PaperMarkdown sourcePos>{'plain'}</PaperMarkdown>);
    expect(marks(container)).toEqual([]);
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `npm test -- --run src/markdown/rehypePrerequisiteHighlight.test.tsx`
Expected: FAIL — `highlights` prop 없음, mark 0개.

- [ ] **Step 7: 플러그인·렌더러 구현**

`rehypePrerequisiteHighlight.ts`:

```ts
// 원문 범위를 <mark>로 감싼다. rehypeSourcePos보다 먼저 돌아 텍스트 노드를 쪼개 두면,
// 좌표 플러그인이 쪼개진 조각마다 position을 보고 data-src-start를 붙인다.
import { SKIP, visit } from 'unist-util-visit';
import type { Element, Root, Text } from 'hast';
import type { HighlightRange } from './paperContent';

export interface RehypePrerequisiteHighlightOptions {
  /** markdown 좌표 기준 범위(원문 offset + shift). */
  ranges: HighlightRange[];
}

export function rehypePrerequisiteHighlight({ ranges }: RehypePrerequisiteHighlightOptions) {
  const sorted = [...ranges].sort((a, b) => a.start - b.start);
  return (tree: Root, file: { toString(): string }) => {
    if (sorted.length === 0) return;
    const source = String(file);
    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index === undefined) return;
      const start = node.position?.start.offset;
      const end = node.position?.end.offset;
      if (start === undefined || end === undefined) return;
      if (source.slice(start, end) !== node.value) return;

      // 이 노드 안에 완전히 들어오는 범위만 그린다 — 노드 경계를 넘는 범위는 매핑을 보장할 수 없다.
      const inside = sorted.filter((r) => r.start >= start && r.end <= end && r.start < r.end);
      if (inside.length === 0) return;

      const pieces: (Text | Element)[] = [];
      let cursor = start;
      for (const r of inside) {
        if (r.start < cursor) continue; // 겹침은 앞 범위 우선
        if (r.start > cursor) pieces.push(textNode(source, cursor, r.start));
        pieces.push({
          type: 'element',
          tagName: 'mark',
          properties: { className: ['term-highlight'], dataHighlightId: r.id, role: 'button', tabIndex: 0 },
          children: [textNode(source, r.start, r.end)],
        });
        cursor = r.end;
      }
      if (cursor < end) pieces.push(textNode(source, cursor, end));
      parent.children.splice(index, 1, ...pieces);
      return [SKIP, index + pieces.length];
    });
  };
}

function textNode(source: string, start: number, end: number): Text {
  return {
    type: 'text',
    value: source.slice(start, end),
    position: {
      start: { line: 0, column: 0, offset: start },
      end: { line: 0, column: 0, offset: end },
    },
  };
}
```

`rehypeSourcePos`는 텍스트 노드의 `position.start.offset`만 쓰므로 `line`·`column`은 0으로 둬도 된다. `mark` 요소 자체는 `position`이 없어 `rehypeSourcePos`가 건너뛰고, 그 안의 텍스트 노드는 position이 있어 span으로 감싸진다.

`PaperMarkdown.tsx`:

```tsx
import { rehypePrerequisiteHighlight } from './rehypePrerequisiteHighlight';
import type { HighlightRange } from './paperContent';

export function PaperMarkdown({ children, onImageError, sourcePos = false, highlights }: {
  children: string;
  onImageError?: () => void;
  sourcePos?: boolean;
  /** markdown 좌표 기준 선행지식 범위. sourcePos일 때만 그린다. */
  highlights?: HighlightRange[];
}) {
  const katex: [typeof rehypeKatex, { throwOnError: boolean }] = [rehypeKatex, { throwOnError: false }];
  const highlight: [typeof rehypePrerequisiteHighlight, { ranges: HighlightRange[] }] =
    [rehypePrerequisiteHighlight, { ranges: highlights ?? [] }];
  // 하이라이트 → 좌표 → KaTeX 순서. 쪼갠 조각마다 좌표가 붙어야 선택 anchor가 밀리지 않는다.
  const plugins = sourcePos ? [highlight, rehypeSourcePos, katex] : [katex];
```

`rehypePlugins={plugins}`로 교체. `components`에 `mark` 오버라이드는 필요 없다(react-markdown이 `data-highlight-id`·`role`·`tabIndex`를 그대로 넘긴다). 안 넘기면 `mark(props) { return <mark {...props} /> }`를 추가한다.

`markdown.css`에 Design v3 값 그대로:

```css
/* 선행지식 하이라이트 (Design v3). 토글이 꺼지면 .pt-prerequisite-off 아래서 스타일만 지운다. */
.term-highlight{padding:0 2px;border-radius:3px;background:#dce8f7;color:#1e2e42;box-shadow:inset 0 -1px 0 #8299b5;cursor:pointer;box-decoration-break:clone;-webkit-box-decoration-break:clone}
.term-highlight:hover,.term-highlight[aria-expanded="true"]{background:#cfdef0;box-shadow:inset 0 -2px 0 #637f9f}
.pt-prerequisite-off .term-highlight{padding:0;background:transparent;color:inherit;box-shadow:none;cursor:text}
```

`PaperViewer.tsx`: `PaperViewerProps`에 `prerequisiteVisible: boolean;` 추가. `PaperSheet`를 감싸는 요소 또는 `PaperSheet`에 `className={prerequisiteVisible ? undefined : 'pt-prerequisite-off'}`를 준다(`PaperSheet`가 className을 받는지 확인, 안 받으면 바깥 `div`에). 블록 렌더에서:

```tsx
                      <PaperMarkdown
                        sourcePos
                        onImageError={onImageError}
                        highlights={b.highlights?.map((h) => ({
                          id: h.id, start: h.start + (b.sourceOffsetShift ?? 0), end: h.end + (b.sourceOffsetShift ?? 0),
                        }))}
                      >
                        {b.markdown ?? ''}
                      </PaperMarkdown>
```

- [ ] **Step 8: 통과 확인**

Run: `npm test -- --run src/markdown/rehypePrerequisiteHighlight.test.tsx`
Expected: 7 PASS. 실패하면 react-markdown이 hast 속성명을 어떻게 넘기는지(`dataHighlightId` → `data-highlight-id`) 확인한다.

- [ ] **Step 9: 선택 anchor 회귀 테스트**

`sourceOffset.test.ts`의 기존 `resolveOffset` 테스트 방식을 보고 같은 형태로 추가한다.

```ts
it('하이라이트로 쪼개진 뒤쪽 조각에서도 원문 offset이 맞는다', () => {
  const { container } = render(
    <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 8, end: 15 }]}>{'softmax dropout layer'}</PaperMarkdown>,
  );
  const block = { id: 'b', type: 'para', markdown: 'softmax dropout layer', sourceText: 'softmax dropout layer', sourceOffsetShift: 0 } as const;
  const tail = Array.from(container.querySelectorAll('[data-src-start]')).at(-1)!.firstChild as Text;
  // ' layer'의 두 번째 글자 'l' = 원문 16
  expect(resolveOffset(tail, 1, block, 'start')).toBe(16);
});
```

파일이 `.ts`라 JSX를 못 쓰면 `sourceOffset.highlight.test.tsx`로 새 파일을 만든다.

Run: `npm test -- --run src/routes/study/`
Expected: PASS. `useTextSelection`·`selectionAnchors` 기존 테스트도 그대로 통과.

- [ ] **Step 10: 전체 FE 검사와 커밋**

Run: `npm run typecheck && npm test -- --run`
Expected: 오류 0, 모두 PASS.

```bash
git add fe/src/api/types.ts fe/src/api/papers.ts fe/src/markdown/paperContent.ts \
  fe/src/markdown/rehypePrerequisiteHighlight.ts fe/src/markdown/PaperMarkdown.tsx fe/src/markdown/markdown.css \
  fe/src/routes/study/PaperViewer.tsx \
  fe/src/markdown/rehypePrerequisiteHighlight.test.tsx fe/src/markdown/paperContent.test.ts \
  fe/src/routes/study/sourceOffset.test.ts
git commit -m "[YMC-414] feat(fe): 선행지식 하이라이트 타입·API와 본문 mark 렌더"
```

(새 테스트 파일을 만들었으면 그 경로로 바꾼다.)

---

### Task 7: FE 토글과 팝오버

**Files:**
- Create: `fe/src/routes/study/PrerequisiteToggle.tsx`
- Create: `fe/src/routes/study/PrerequisiteLayer.tsx`
- Create: `fe/src/routes/study/prerequisiteStatus.ts`
- Modify: `fe/src/routes/StudyPage.tsx`
- Modify: `fe/src/routes/study/SelectionLayer.tsx` (열림 콜백)
- Modify: `fe/src/routes/study/ContentAskLayer.tsx` (열림 콜백)
- Test: `fe/src/routes/study/PrerequisiteToggle.test.tsx`
- Test: `fe/src/routes/study/PrerequisiteLayer.test.tsx`
- Test: `fe/src/routes/study/prerequisiteStatus.test.ts`
- Test: `fe/src/routes/StudyPage.test.tsx` (케이스 추가)

**Interfaces:**
- Consumes: `createPrerequisiteDefinition`, `PaperContent.prerequisiteHighlights`, `computeToolbarPosition`, `PaperViewer.prerequisiteVisible`.
- Produces:
  - `prerequisiteDisabledReason(count: number): string | undefined` — 0이면 `'표시할 선행지식이 없습니다'`.
  - `PrerequisiteToggle({ checked, disabled, disabledReason, onToggle })`.
  - `PrerequisiteLayer({ paperId, viewerRef, highlights, visible, openSignal, onOpen })` — `openSignal: number`는 다른 오버레이가 열릴 때 증가해 팝오버를 닫는 신호. `onOpen()`은 팝오버가 열릴 때 호출.
  - `SelectionLayer`·`ContentAskLayer`에 `closeSignal?: number`(증가하면 idle로)와 `onOpen?: () => void` prop 추가.

- [ ] **Step 1: 상태 함수 테스트와 구현**

```ts
// prerequisiteStatus.test.ts
import { describe, expect, it } from 'vitest';
import { prerequisiteDisabledReason } from './prerequisiteStatus';

describe('prerequisiteDisabledReason', () => {
  it('하이라이트가 없으면 안내 문구', () => {
    expect(prerequisiteDisabledReason(0)).toBe('표시할 선행지식이 없습니다');
  });
  it('있으면 undefined', () => {
    expect(prerequisiteDisabledReason(3)).toBeUndefined();
  });
});
```

Run: `npm test -- --run src/routes/study/prerequisiteStatus.test.ts` → FAIL.

```ts
// prerequisiteStatus.ts
// 선행지식 토글의 비활성 사유. 하이라이트 배열이 비면 Design v3 툴팁 문구를 돌려준다.
export function prerequisiteDisabledReason(highlightCount: number): string | undefined {
  return highlightCount === 0 ? '표시할 선행지식이 없습니다' : undefined;
}
```

Run → PASS.

- [ ] **Step 2: 토글 테스트**

```tsx
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PrerequisiteToggle } from './PrerequisiteToggle';

describe('PrerequisiteToggle', () => {
  it('role=switch이고 aria-checked를 반영한다', () => {
    render(<PrerequisiteToggle checked disabled={false} onToggle={() => {}} />);
    expect(screen.getByRole('switch', { name: '선행지식' })).toHaveAttribute('aria-checked', 'true');
  });

  it('누르면 onToggle', () => {
    const onToggle = vi.fn();
    render(<PrerequisiteToggle checked={false} disabled={false} onToggle={onToggle} />);
    fireEvent.click(screen.getByRole('switch'));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('비활성이면 사유가 title에 있고 눌러도 onToggle이 없다', () => {
    const onToggle = vi.fn();
    render(<PrerequisiteToggle checked={false} disabled disabledReason="표시할 선행지식이 없습니다" onToggle={onToggle} />);
    const sw = screen.getByRole('switch');
    expect(sw).toBeDisabled();
    expect(sw).toHaveAttribute('title', '표시할 선행지식이 없습니다');
    fireEvent.click(sw);
    expect(onToggle).not.toHaveBeenCalled();
  });
});
```

FE에는 `@testing-library/jest-dom`이 없다(`TranslationModeButton.test.tsx`는 `getAttribute` 비교를 쓴다). 위 테스트의 `toHaveAttribute('x', 'y')`는 `expect(el.getAttribute('x')).toBe('y')`로, `toBeDisabled()`는 `expect((el as HTMLButtonElement).disabled).toBe(true)`로, `toHaveTextContent('t')`는 `expect(el.textContent).toContain('t')`로 바꿔 쓴다. Task 7의 `PrerequisiteLayer.test.tsx`도 같다.

Run → FAIL.

- [ ] **Step 3: 토글 구현**

```tsx
// 상단바 선행지식 토글 (Design v3 role=switch). 스타일은 아트보드 .prerequisite-toggle 값 그대로.
export interface PrerequisiteToggleProps {
  checked: boolean;
  disabled: boolean;
  /** 비활성 사유 — 툴팁으로 보인다. */
  disabledReason?: string;
  onToggle: () => void;
}

export function PrerequisiteToggle({ checked, disabled, disabledReason, onToggle }: PrerequisiteToggleProps) {
  const on = checked && !disabled;
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label="선행지식"
      disabled={disabled}
      title={disabled ? disabledReason : '선행지식'}
      onClick={onToggle}
      style={{
        display: 'flex', alignItems: 'center', gap: '9px', padding: '8px 0',
        background: 'transparent', border: 'none', cursor: disabled ? 'default' : 'pointer',
        color: on ? '#fffdf7' : 'rgba(255,253,247,0.7)', opacity: disabled ? 0.5 : 1,
        fontFamily: 'var(--font-sans)', fontSize: '14px',
      }}
    >
      <span>선행지식</span>
      <span aria-hidden="true" style={{
        position: 'relative', width: '34px', height: '19px', borderRadius: '10px',
        background: on ? '#b8924e' : 'rgba(255,253,247,0.25)', transition: 'background .15s',
      }}>
        <span style={{
          position: 'absolute', top: '2px', left: '2px', width: '15px', height: '15px', borderRadius: '50%',
          background: on ? '#fffdf7' : 'rgba(255,253,247,0.8)', transform: on ? 'translateX(15px)' : 'none',
          transition: 'transform .15s',
        }} />
      </span>
    </button>
  );
}
```

아트보드 `.switch-track`·`.switch-thumb` 치수는 `Paper Study Page - Prerequisite Knowledge.dc.html` 29~32행에서 확인해 맞춘다.

Run → PASS.

- [ ] **Step 4: 팝오버 레이어 테스트**

```tsx
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createRef } from 'react';
import { PrerequisiteLayer } from './PrerequisiteLayer';
import * as papersApi from '../../api/papers';
import { ApiError } from '../../api/types';

vi.mock('../../api/papers');

const highlights = [{ highlightId: 'h1', blockId: 'b', startOffset: 0, endOffset: 7, text: 'softmax' }];

function setup(opts: { visible?: boolean; openSignal?: number; onOpen?: () => void } = {}) {
  const viewerRef = createRef<HTMLDivElement>();
  const utils = render(
    <div>
      <div ref={viewerRef} data-testid="viewer" style={{ position: 'relative' }}>
        <p><mark className="term-highlight" data-highlight-id="h1" role="button" tabIndex={0}>softmax</mark> rest</p>
      </div>
      <PrerequisiteLayer
        paperId="p" viewerRef={viewerRef} highlights={highlights}
        visible={opts.visible ?? true} openSignal={opts.openSignal ?? 0} onOpen={opts.onOpen ?? (() => {})}
      />
      <aside data-testid="tutor">tutor</aside>
    </div>,
  );
  return { ...utils, viewerRef };
}

describe('PrerequisiteLayer', () => {
  beforeEach(() => vi.mocked(papersApi.createPrerequisiteDefinition).mockReset());

  it('하이라이트를 누르면 로딩 팝오버가 열리고 완료되면 설명으로 바뀐다', async () => {
    let resolve!: (v: papersApi.PrerequisiteDefinitionResponse) => void;
    vi.mocked(papersApi.createPrerequisiteDefinition).mockReturnValue(new Promise((r) => { resolve = r; }));
    setup();

    fireEvent.click(screen.getByText('softmax'));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('softmax');
    expect(dialog.querySelector('.pt-prereq-loading')).not.toBeNull();

    await act(async () => { resolve({ term: 'softmax', definitionEn: 'An en', definitionKo: '국문' }); });
    expect(screen.getByRole('dialog')).toHaveTextContent('An en');
    expect(screen.getByRole('dialog')).toHaveTextContent('국문');
    expect(screen.getByText('softmax', { selector: 'mark' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('실패하면 같은 팝오버에 실패 문구', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockRejectedValue(new ApiError('x', 'PREREQUISITE_DEFINITION_FAILED', 502));
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog')).toHaveTextContent('설명을 불러오지 못했습니다.'));
  });

  it('429도 같은 실패 문구', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockRejectedValue(new ApiError('x', 'PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED', 429));
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog')).toHaveTextContent('설명을 불러오지 못했습니다.'));
  });

  it('뷰어 빈 영역을 누르면 닫히고, 팝오버 안·튜터는 닫지 않는다', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockResolvedValue({ term: 'softmax', definitionEn: 'e', definitionKo: 'k' });
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => screen.getByRole('dialog'));

    fireEvent.mouseDown(screen.getByRole('dialog'));
    expect(screen.queryByRole('dialog')).not.toBeNull();
    fireEvent.mouseDown(screen.getByTestId('tutor'));
    expect(screen.queryByRole('dialog')).not.toBeNull();
    fireEvent.mouseDown(screen.getByText('rest'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('닫았다 다시 누르면 요청 없이 즉시 열린다', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockResolvedValue({ term: 'softmax', definitionEn: 'e', definitionKo: 'k' });
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog')).toHaveTextContent('e'));
    fireEvent.mouseDown(screen.getByText('rest'));

    fireEvent.click(screen.getByText('softmax'));
    expect(screen.getByRole('dialog')).toHaveTextContent('e');
    expect(papersApi.createPrerequisiteDefinition).toHaveBeenCalledTimes(1);
  });

  it('실패는 기억하지 않아 다시 누르면 새 요청이다', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockRejectedValueOnce(new ApiError('x', 'PREREQUISITE_DEFINITION_FAILED', 502));
    vi.mocked(papersApi.createPrerequisiteDefinition).mockResolvedValueOnce({ term: 'softmax', definitionEn: 'ok', definitionKo: 'k' });
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog')).toHaveTextContent('설명을 불러오지 못했습니다.'));
    fireEvent.mouseDown(screen.getByText('rest'));

    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog')).toHaveTextContent('ok'));
    expect(papersApi.createPrerequisiteDefinition).toHaveBeenCalledTimes(2);
  });

  it('visible이 false면 클릭을 무시한다', () => {
    setup({ visible: false });
    fireEvent.click(screen.getByText('softmax'));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(papersApi.createPrerequisiteDefinition).not.toHaveBeenCalled();
  });

  it('openSignal이 바뀌면 닫힌다', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockResolvedValue({ term: 'softmax', definitionEn: 'e', definitionKo: 'k' });
    const { rerender, viewerRef } = setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => screen.getByRole('dialog'));
    rerender(
      <div>
        <div ref={viewerRef} data-testid="viewer"><p><mark className="term-highlight" data-highlight-id="h1" role="button" tabIndex={0}>softmax</mark> rest</p></div>
        <PrerequisiteLayer paperId="p" viewerRef={viewerRef} highlights={highlights} visible openSignal={1} onOpen={() => {}} />
      </div>,
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('열릴 때 onOpen을 부른다', () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockReturnValue(new Promise(() => {}));
    const onOpen = vi.fn();
    setup({ onOpen });
    fireEvent.click(screen.getByText('softmax'));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });
});
```

`ApiError` 생성자 시그니처는 `api/types.ts:52`를 따른다. jsdom에서 `getBoundingClientRect`는 0을 돌려주므로 위치 검증은 하지 않는다.

Run: `npm test -- --run src/routes/study/PrerequisiteLayer.test.tsx` → FAIL(모듈 없음).

- [ ] **Step 5: 팝오버 레이어 구현**

```tsx
// 선행지식 팝오버 (Design v3). 뷰어 안 mark 클릭을 위임으로 받아 선택 구간 아래에 연다.
// 성공한 설명은 highlightId별로 페이지 메모리에 두어 다시 열 때 요청 없이 보여준다.
import { useEffect, useRef, useState, type RefObject } from 'react';
import { createPrerequisiteDefinition } from '../../api/papers';
import type { PrerequisiteDefinitionResponse, PrerequisiteHighlight } from '../../api/types';
import { computeToolbarPosition } from './selectionPosition';

export interface PrerequisiteLayerProps {
  paperId: string;
  viewerRef: RefObject<HTMLDivElement | null>;
  highlights: PrerequisiteHighlight[];
  /** 토글 상태. false면 클릭을 무시하고 열린 팝오버를 닫는다. */
  visible: boolean;
  /** 다른 오버레이가 열릴 때 증가한다. 바뀌면 닫는다. */
  openSignal: number;
  /** 팝오버가 열릴 때 호출 — StudyPage가 다른 오버레이를 닫는다. */
  onOpen: () => void;
}

const POPOVER_SIZE = { width: 430, height: 200 };

type Layer =
  | { phase: 'idle' }
  | { phase: 'loading'; id: string; term: string; rect: DOMRect; mark: HTMLElement }
  | { phase: 'success'; id: string; term: string; rect: DOMRect; mark: HTMLElement; en: string; ko: string }
  | { phase: 'failed'; id: string; term: string; rect: DOMRect; mark: HTMLElement };

export function PrerequisiteLayer({ paperId, viewerRef, highlights, visible, openSignal, onOpen }: PrerequisiteLayerProps) {
  const [layer, setLayer] = useState<Layer>({ phase: 'idle' });
  const popupRef = useRef<HTMLDivElement>(null);
  const memoRef = useRef(new Map<string, PrerequisiteDefinitionResponse>());
  const baseScrollTopRef = useRef(0);
  const [scrollDelta, setScrollDelta] = useState(0);

  // 본문이 바뀌면(하이라이트 배열 교체) 기억한 설명을 비운다.
  useEffect(() => { memoRef.current.clear(); }, [highlights]);

  // 토글 off 또는 다른 오버레이 열림 → 닫기.
  useEffect(() => { setLayer({ phase: 'idle' }); }, [visible, openSignal]);

  // mark의 aria-expanded 동기화.
  useEffect(() => {
    if (layer.phase === 'idle') return;
    layer.mark.setAttribute('aria-expanded', 'true');
    return () => layer.mark.setAttribute('aria-expanded', 'false');
  }, [layer]);

  // 하이라이트 클릭 위임.
  useEffect(() => {
    const el = viewerRef.current;
    if (!el) return;
    function handleClick(e: MouseEvent) {
      if (!visible) return;
      const mark = (e.target as Element).closest?.('mark.term-highlight') as HTMLElement | null;
      if (!mark || !el!.contains(mark)) return;
      const id = mark.getAttribute('data-highlight-id');
      const h = highlights.find((x) => x.highlightId === id);
      if (!id || !h) return;
      e.preventDefault();
      const rect = mark.getBoundingClientRect();
      baseScrollTopRef.current = el!.scrollTop;
      setScrollDelta(0);
      onOpen();
      const memo = memoRef.current.get(id);
      if (memo) {
        setLayer({ phase: 'success', id, term: h.text, rect, mark, en: memo.definitionEn, ko: memo.definitionKo });
        return;
      }
      setLayer({ phase: 'loading', id, term: h.text, rect, mark });
    }
    el.addEventListener('click', handleClick);
    return () => el.removeEventListener('click', handleClick);
  }, [viewerRef, highlights, visible, onOpen]);

  // loading 진입에 반응해 요청한다. 닫히거나 다른 하이라이트로 바뀌면 abort.
  useEffect(() => {
    if (layer.phase !== 'loading') return;
    const { id, term, rect, mark } = layer;
    const controller = new AbortController();
    createPrerequisiteDefinition(paperId, id, controller.signal)
      .then((res) => {
        memoRef.current.set(id, res);
        setLayer((prev) => (prev.phase === 'loading' && prev.id === id
          ? { phase: 'success', id, term, rect, mark, en: res.definitionEn, ko: res.definitionKo } : prev));
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        console.warn('선행지식 설명 실패', err);
        setLayer((prev) => (prev.phase === 'loading' && prev.id === id ? { phase: 'failed', id, term, rect, mark } : prev));
      });
    return () => controller.abort();
  }, [layer.phase === 'loading' ? layer.id : null, paperId]); // eslint-disable-line react-hooks/exhaustive-deps

  // 뷰어 스크롤을 따라간다.
  useEffect(() => {
    const el = viewerRef.current;
    if (!el) return;
    const onScroll = () => setScrollDelta(el.scrollTop - baseScrollTopRef.current);
    el.addEventListener('scroll', onScroll);
    return () => el.removeEventListener('scroll', onScroll);
  }, [viewerRef]);

  // 바깥 클릭 판정: 팝오버 안·선택한 mark·뷰어 밖(목차·튜터)은 무시, 그 외 뷰어 영역이면 닫는다.
  useEffect(() => {
    if (layer.phase === 'idle') return;
    const { mark } = layer;
    function handleDocMouseDown(e: MouseEvent) {
      const target = e.target as Node;
      if (popupRef.current?.contains(target)) return;
      if (mark.contains(target)) return;
      if (!viewerRef.current?.contains(target)) return;
      setLayer({ phase: 'idle' });
    }
    document.addEventListener('mousedown', handleDocMouseDown, true);
    return () => document.removeEventListener('mousedown', handleDocMouseDown, true);
  }, [layer, viewerRef]);

  if (layer.phase === 'idle' || !viewerRef.current) return null;
  const containerRect = viewerRef.current.getBoundingClientRect();
  const pos = computeToolbarPosition(layer.rect, containerRect, POPOVER_SIZE);

  return (
    <div
      ref={popupRef}
      role="dialog"
      aria-modal="false"
      aria-label={layer.term}
      onMouseDown={(e) => e.preventDefault()}
      style={{
        position: 'absolute', top: pos.top - scrollDelta, left: pos.left, width: POPOVER_SIZE.width,
        border: '1px solid #cdbb9d', borderRadius: 7, background: '#fffdf8',
        boxShadow: '0 5px 14px rgba(66,49,31,.14)', zIndex: 5,
      }}
    >
      <div style={{ position: 'relative', padding: '25px 22px 24px' }}>
        <h2 style={{ margin: '0 0 10px', fontFamily: "Georgia,'Times New Roman',serif", fontSize: 21, fontWeight: 700, lineHeight: 1.25, color: '#18385e' }}>
          {layer.term}
        </h2>
        {layer.phase === 'loading' && (
          <div className="pt-prereq-loading" aria-label="설명 생성 중" style={{ display: 'flex', gap: 6, padding: '6px 0' }}>
            <span className="pt-prereq-dot" /><span className="pt-prereq-dot" /><span className="pt-prereq-dot" />
          </div>
        )}
        {layer.phase === 'failed' && (
          <p style={{ margin: 0, fontFamily: 'var(--font-sans)', fontSize: 15, color: '#6d6861' }}>설명을 불러오지 못했습니다.</p>
        )}
        {layer.phase === 'success' && (
          <>
            <p style={{ margin: 0, fontFamily: "Georgia,'Times New Roman',serif", fontSize: 14, lineHeight: 1.5, color: '#3c3935' }}>{layer.en}</p>
            <div style={{ height: 1, margin: '20px 0 16px', background: '#ded3c3' }} />
            <p style={{ margin: 0, fontFamily: 'var(--font-sans)', fontSize: 16, lineHeight: 1.65, color: '#45413c' }}>{layer.ko}</p>
          </>
        )}
      </div>
    </div>
  );
}
```

`markdown.css`에 점 3개 로딩과 말풍선 화살표:

```css
.pt-prereq-dot{width:6px;height:6px;border-radius:50%;background:#8299b5;animation:pt-prereq-blink 1s infinite}
.pt-prereq-dot:nth-child(2){animation-delay:.2s}
.pt-prereq-dot:nth-child(3){animation-delay:.4s}
@keyframes pt-prereq-blink{0%,80%,100%{opacity:.25}40%{opacity:1}}
```

화살표(`::before`)는 inline style로 못 그리므로 팝오버 루트에 `className="pt-prereq-popover"`를 주고 CSS로:

```css
.pt-prereq-popover::before{content:"";position:absolute;top:-11px;left:99px;width:20px;height:20px;background:#fffdf8;border-left:1px solid #cdbb9d;border-top:1px solid #cdbb9d;transform:rotate(45deg)}
```

Run: `npm test -- --run src/routes/study/PrerequisiteLayer.test.tsx` → PASS. jsdom에서 `viewerRef.current.getBoundingClientRect`는 0이라 위치는 0,8 근처로 나온다. 문제없다.

- [ ] **Step 6: 기존 오버레이에 닫힘 신호와 열림 콜백 추가**

`SelectionLayer.tsx`: props에 `closeSignal?: number; onOpen?: () => void;` 추가. `useEffect(() => { if (closeSignal === undefined) return; setLayer((prev) => { if (prev.phase !== 'idle') prev.clear(); return { phase: 'idle' }; }); }, [closeSignal]);`를 추가하되 **첫 렌더에서는 실행하지 않도록** `useRef`로 이전 값을 비교한다. `toolbar`로 진입하는 effect(`if (sel) setLayer(...)`) 안에서 `sel`이 생길 때 `onOpen?.()`을 부른다.

`ContentAskLayer.tsx`: 같은 방식으로 `closeSignal`·`onOpen` 추가. 팝오버가 열리는 지점(`setLayer({ phase: 'open', ... })` 같은 곳)에서 `onOpen?.()`.

주의: `onOpen`이 매 렌더 새 함수면 effect가 재등록된다. `StudyPage`에서 `useCallback`으로 고정한다.

- [ ] **Step 7: StudyPage 배선과 테스트**

`StudyPage.test.tsx`에 추가(기존 mock 구조를 따른다):

```tsx
it('knowledgeGraphStatus가 READY로 바뀌면 본문을 다시 조회한다', async () => {
  // 기존 translationStatus READY 재조회 테스트를 복사해 knowledgeGraphStatus로 바꾼다.
});

it('하이라이트가 없으면 토글이 비활성이고 툴팁이 있다', async () => {
  // getPaperContent mock이 prerequisiteHighlights: [] 를 돌려주게 한 뒤
  // screen.getByRole('switch', { name: '선행지식' })가 disabled이고 title이 '표시할 선행지식이 없습니다'인지.
});

it('토글을 켜면 뷰어에 pt-prerequisite-off가 빠지고, 끄면 다시 붙는다', async () => {
  // prerequisiteHighlights 1개. 처음엔 .pt-prerequisite-off 존재 → click switch → 없음 → click → 존재.
});
```

세 테스트의 본문은 기존 `StudyPage.test.tsx`의 헬퍼(`renderStudyPage` 같은 것)와 mock 데이터 형태를 보고 채운다. 채울 때 `PaperContent` mock에 `prerequisiteHighlights` 필드를 넣는다.

Run → FAIL.

`StudyPage.tsx` 수정:

- import: `PrerequisiteToggle`, `PrerequisiteLayer`, `prerequisiteDisabledReason`, `useCallback`.
- 상태: `const [prerequisiteOn, setPrerequisiteOn] = useState(false);`, `const [overlaySignals, setOverlaySignals] = useState({ prerequisite: 0, selection: 0, ask: 0 });`
- READY 재조회: 기존 translation effect 아래에 같은 모양으로 `knowledgeGraphStatus` 버전 추가(`prevKnowledgeGraphStatus` ref).
- 파생: `const prerequisiteHighlights = contentQuery.data?.prerequisiteHighlights ?? [];` (useMemo로 안정화), `const prerequisiteEnabled = prerequisiteHighlights.length > 0;`, `const prerequisiteVisible = prerequisiteOn && prerequisiteEnabled;`
- 콜백:

```tsx
  const handlePrerequisiteOpen = useCallback(() => {
    setOverlaySignals((s) => ({ ...s, selection: s.selection + 1, ask: s.ask + 1 }));
  }, []);
  const handleSelectionOpen = useCallback(() => {
    setOverlaySignals((s) => ({ ...s, prerequisite: s.prerequisite + 1, ask: s.ask + 1 }));
  }, []);
  const handleAskOpen = useCallback(() => {
    setOverlaySignals((s) => ({ ...s, prerequisite: s.prerequisite + 1, selection: s.selection + 1 }));
  }, []);
```

- 상단바 `rightSlot`의 `TranslationModeButton` 앞에:

```tsx
            <PrerequisiteToggle
              checked={prerequisiteOn}
              disabled={!prerequisiteEnabled}
              disabledReason={prerequisiteDisabledReason(prerequisiteHighlights.length)}
              onToggle={() => setPrerequisiteOn((v) => !v)}
            />
```

- 뷰어 칸: `PaperViewer`에 `prerequisiteVisible={prerequisiteVisible}`, `SelectionLayer`에 `closeSignal={overlaySignals.selection} onOpen={handleSelectionOpen}`, `ContentAskLayer`에 `closeSignal={overlaySignals.ask} onOpen={handleAskOpen}`, 그 다음 형제로:

```tsx
            <PrerequisiteLayer
              paperId={paperId}
              viewerRef={viewerRef}
              highlights={prerequisiteHighlights}
              visible={prerequisiteVisible}
              openSignal={overlaySignals.prerequisite}
              onOpen={handlePrerequisiteOpen}
            />
```

Run: `npm test -- --run src/routes/StudyPage.test.tsx` → PASS.

- [ ] **Step 8: 상호 닫힘 회귀와 전체 검사**

`SelectionLayer.test.tsx`·`ContentAskLayer.test.tsx`에 각각 1개 추가: `closeSignal`을 0→1로 rerender하면 열린 팝업이 idle이 되는지.

Run: `npm run typecheck && npm test -- --run`
Expected: 오류 0, 모두 PASS.

- [ ] **Step 9: 커밋**

```bash
git add fe/src/routes/study/PrerequisiteToggle.tsx fe/src/routes/study/PrerequisiteLayer.tsx \
  fe/src/routes/study/prerequisiteStatus.ts fe/src/routes/StudyPage.tsx \
  fe/src/routes/study/SelectionLayer.tsx fe/src/routes/study/ContentAskLayer.tsx fe/src/markdown/markdown.css \
  fe/src/routes/study/PrerequisiteToggle.test.tsx fe/src/routes/study/PrerequisiteLayer.test.tsx \
  fe/src/routes/study/prerequisiteStatus.test.ts fe/src/routes/StudyPage.test.tsx \
  fe/src/routes/study/SelectionLayer.test.tsx fe/src/routes/study/ContentAskLayer.test.tsx
git commit -m "[YMC-414] feat(fe): 선행지식 토글과 설명 팝오버"
```

---

### Task 8: 로컬 실행 확인과 마무리

- [ ] **Step 1: 로컬 환경 확인**

`app/be/compose.yml`·`.env.example`에 YMC-413 작업분(Valkey 환경변수)이 커밋 전 상태로 공유 checkout에 있다. 이 worktree에는 없으므로 로컬 실행 시 환경변수를 직접 준다: `SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=6379 PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT=prerequisite-knowledge-agent-v1`. Valkey 컨테이너는 `docker run -d -p 6379:6379 valkey/valkey:7.2.14-alpine`.

- [ ] **Step 2: 실화면 확인 항목**

dev는 YMC-413 적용 뒤. 로컬에서 AI 서버가 있으면: 새 PDF 등록 → compile 완료 → 토글 활성 → 하이라이트 표시 → 클릭 → 로딩 → 설명 → 닫기 → 재클릭 즉시 → 번역 팝업과 상호 닫힘 → 하이라이트 있는 문단에서 드래그 번역 범위가 맞는지.

- [ ] **Step 3: PR 준비**

PR 본문은 `## 배경` · `## 변경사항` · `## 검증`. 의존 섹션은 쓰지 않는다. `## 검증`에 "dev 실화면은 YMC-413 Valkey 적용 뒤"를 적는다. 커밋·푸시·PR 생성은 각각 사용자 승인 후.
